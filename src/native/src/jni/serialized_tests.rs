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
