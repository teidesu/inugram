use super::*;
use std::sync::mpsc;

#[test]
fn entry_rejects_reentry_and_times_out_under_contention() {
  let slot = Serialized::new(42);
  let lease = slot.enter(None).unwrap();
  assert_eq!(slot.enter(None).err(), Some(EntryError::Reentrant));
  thread::scope(|scope| {
    scope
      .spawn(|| {
        assert_eq!(slot.enter(Some(Duration::from_millis(1))).err(), Some(EntryError::Busy));
      })
      .join()
      .unwrap();
  });
  assert_eq!(*lease, 42);
  drop(lease);
  assert_eq!(*slot.enter(None).unwrap(), 42);
}

#[test]
fn waiting_entries_receive_the_same_value() {
  let slot = Serialized::new(Mutex::new(0));
  thread::scope(|scope| {
    for _ in 0..8 {
      let slot = &slot;
      scope.spawn(move || {
        for _ in 0..100 {
          *slot.enter(None).unwrap().lock().unwrap() += 1;
        }
      });
    }
  });
  assert_eq!(*slot.enter(None).unwrap().lock().unwrap(), 800);
}

#[test]
fn close_waits_for_active_entry_and_rejects_later_entries() {
  let slot = Serialized::new(42);
  let lease = slot.enter(None).unwrap();
  assert_eq!(slot.close(), Err(EntryError::Reentrant));
  let (started, received) = mpsc::channel();
  thread::scope(|scope| {
    let slot = &slot;
    let closer = scope.spawn(move || {
      started.send(()).unwrap();
      slot.close()
    });
    received.recv_timeout(Duration::from_secs(5)).unwrap();
    drop(lease);
    assert_eq!(closer.join().unwrap(), Ok(Some(42)));
  });
  assert_eq!(slot.enter(None).err(), Some(EntryError::Closed));
  assert_eq!(slot.close(), Ok(None));
}

#[test]
fn admission_stops_while_another_thread_holds_the_lease() {
  let slot = Serialized::new(42);
  let lease = slot.enter(None).unwrap();
  assert!(lease.is_admitting());
  thread::scope(|scope| {
    scope.spawn(|| slot.stop_admitting()).join().unwrap();
  });
  assert!(!lease.is_admitting());
  drop(lease);
  assert!(!slot.enter(None).unwrap().is_admitting());
}

#[test]
fn a_lent_value_serves_other_threads_and_returns_to_the_lender() {
  let slot = Serialized::new(Mutex::new(0));
  let lease = slot.enter(None).unwrap();
  let value = &*lease as *const Mutex<i32>;
  let (result, _) = unsafe {
    slot.lend(value, || {
      thread::scope(|scope| {
        for _ in 0..4 {
          scope.spawn(|| {
            let borrowed = slot.enter(Some(Duration::from_secs(5))).unwrap();
            assert!(borrowed.is_borrowed());
            *borrowed.lock().unwrap() += 1;
          });
        }
      });
      "done"
    })
  };
  assert_eq!(result, "done");
  assert!(!lease.is_borrowed());
  assert_eq!(*lease.lock().unwrap(), 4);
  thread::scope(|scope| {
    scope.spawn(|| assert_eq!(slot.enter(Some(Duration::from_millis(1))).err(), Some(EntryError::Busy)));
  });
  drop(lease);
  assert_eq!(*slot.enter(None).unwrap().lock().unwrap(), 4);
}

#[test]
fn the_lending_thread_itself_can_borrow() {
  let slot = Serialized::new(7);
  let lease = slot.enter(None).unwrap();
  let (seen, _) = unsafe { slot.lend(&*lease, || *slot.enter(None).unwrap()) };
  assert_eq!(seen, 7);
  assert_eq!(slot.enter(None).err(), Some(EntryError::Reentrant));
}

#[test]
fn the_lender_waits_for_the_borrower_before_taking_the_value_back() {
  let slot = Serialized::new(1);
  let lease = slot.enter(None).unwrap();
  let (borrowed, release) = (mpsc::channel(), mpsc::channel::<()>());
  let (_, waited) = thread::scope(|scope| {
    let slot = &slot;
    let (borrowed_tx, release_rx) = (borrowed.0, release.1);
    scope.spawn(move || {
      let _borrowed = slot.enter(Some(Duration::from_secs(5))).unwrap();
      borrowed_tx.send(()).unwrap();
      release_rx.recv().unwrap();
      thread::sleep(Duration::from_millis(50));
    });
    unsafe {
      slot.lend(&*lease, || {
        borrowed.1.recv_timeout(Duration::from_secs(5)).unwrap();
        release.0.send(()).unwrap();
      })
    }
  });
  assert!(waited >= Duration::from_millis(40), "waited {waited:?}");
  drop(lease);
}

#[test]
fn close_while_lent_waits_for_the_lender_to_finish() {
  let slot = Serialized::new(42);
  let lease = slot.enter(None).unwrap();
  thread::scope(|scope| {
    let slot = &slot;
    let (lent_tx, lent_rx) = mpsc::channel();
    let (closing_tx, closing_rx) = mpsc::channel();
    let closer = scope.spawn(move || {
      lent_rx.recv().unwrap();
      closing_tx.send(()).unwrap();
      slot.close()
    });
    unsafe {
      slot.lend(&*lease, || {
        lent_tx.send(()).unwrap();
        closing_rx.recv().unwrap();
        while slot.enter(Some(Duration::ZERO)).err() != Some(EntryError::Closed) {
          thread::yield_now();
        }
      })
    };
    assert!(!closer.is_finished());
    drop(lease);
    assert_eq!(closer.join().unwrap(), Ok(Some(42)));
  });
}
