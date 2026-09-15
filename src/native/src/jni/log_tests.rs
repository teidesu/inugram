use rquickjs::{Context, Runtime};

use crate::testing::harness::run_capturing_console;

fn log_lines(source: &str) -> Vec<String> {
  let runtime = Runtime::new().unwrap();
  let context = Context::full(&runtime).unwrap();
  run_capturing_console(&runtime, &context, source)
}

fn log_line(source: &str) -> String {
  let lines = log_lines(source);
  assert_eq!(lines.len(), 1, "{lines:?}");
  lines[0].clone()
}

#[test]
fn every_level_prints_an_error_with_its_stack() {
  let lines = log_lines(
    "const error = new Error('boom'); error.stack = 'trace one\\ntrace two';
     for (const level of ['log', 'info', 'warn', 'error', 'debug']) console[level](error)",
  );
  assert_eq!(lines, vec!["Error: boom\ntrace one\ntrace two"; 5]);
}

#[test]
fn top_level_strings_print_raw_and_other_primitives_inspected() {
  assert_eq!(
    log_line("console.log(null, undefined, 0, '', -0, 1n, Symbol('s'), true, 'it\\'s')"),
    "null undefined 0  -0 1n Symbol(s) true it's"
  );
}

#[test]
fn objects_print_on_one_line_with_quoted_strings_and_keys() {
  assert_eq!(
    log_line("console.log({ a: 1, b: 'x\\n', 'c-d': [1, 2], [Symbol('k')]: null, e: {} })"),
    "{ a: 1, b: 'x\\n', 'c-d': [ 1, 2 ], e: {}, [Symbol(k)]: null }"
  );
}

#[test]
fn nesting_past_two_levels_collapses() {
  assert_eq!(
    log_line("console.log({ a: { b: { c: { d: 1 } }, list: [[[1]]] } })"),
    "{ a: { b: { c: [Object] }, list: [ [Array] ] } }"
  );
}

#[test]
fn a_cycle_prints_as_circular() {
  assert_eq!(log_line("const o = { name: 'o' }; o.self = o; console.log(o)"), "{ name: 'o', self: [Circular] }");
}

#[test]
fn a_long_object_breaks_one_entry_per_line() {
  let a = "a".repeat(40);
  let b = "b".repeat(40);
  assert_eq!(
    log_line(&format!("console.log({{ first: '{a}', nested: {{ second: '{b}', third: '{b}' }} }})")),
    format!("{{\n  first: '{a}',\n  nested: {{\n    second: '{b}',\n    third: '{b}'\n  }}\n}}")
  );
}

#[test]
fn a_long_array_groups_short_items_and_elides_past_a_hundred() {
  let line = log_line("console.log(Array.from({ length: 120 }, (_, i) => i))");
  let rows: Vec<&str> = line.split('\n').collect();
  assert_eq!(rows.len(), 9, "{line}");
  assert_eq!(rows[0], "[");
  assert_eq!(rows[1], "  0,  1,  2,  3,  4,  5,  6,  7,  8,  9,  10, 11, 12, 13, 14, 15, 16, 17, 18,");
  assert_eq!(rows[6], "  95, 96, 97, 98, 99,");
  assert_eq!(rows[7], "  ... 20 more items");
  assert_eq!(rows[8], "]");
}

#[test]
fn holes_in_an_array_are_counted() {
  assert_eq!(log_line("console.log([1, , , 4, ,])"), "[ 1, <2 empty items>, 4, <1 empty item> ]");
}

#[test]
fn builtins_print_their_contents() {
  assert_eq!(
    log_line(
      "console.log(new Map([['a', 1]]), new Set([1]), new Uint8Array([1, 2]), /x/g, new Date(0), new Number(3),
                   new ArrayBuffer(4), new Date(NaN))"
    ),
    "Map(1) { 'a' => 1 } Set(1) { 1 } Uint8Array(2) [ 1, 2 ] /x/g 1970-01-01T00:00:00.000Z [Number: 3] \
     ArrayBuffer { byteLength: 4 } Invalid Date"
  );
}

#[test]
fn functions_and_classes_print_their_kind_and_name() {
  assert_eq!(
    log_line("console.log(function foo() {}, class Bar {}, async () => {}, function* gen() {})"),
    "[Function: foo] [class Bar] [AsyncFunction (anonymous)] [GeneratorFunction: gen]"
  );
}

#[test]
fn instances_name_their_class_and_accessors_are_not_invoked() {
  assert_eq!(
    log_line(
      "class Point { constructor() { this.x = 1 } }
       console.log(new Point(), Object.create(null), { get g() { throw new Error('read') }, set s(v) {}, get gs() { return 1 }, set gs(v) {} })"
    ),
    "Point { x: 1 } [Object: null prototype] {} { g: [Getter], s: [Setter], gs: [Getter/Setter] }"
  );
}

#[test]
fn a_leading_format_string_consumes_the_arguments_after_it() {
  assert_eq!(
    log_line("console.log('%s is %d%% %i %f %j %o%c', 'x', 42, '7.5', '2.5', { a: 1 }, [1], 'css', 'extra', { b: 2 })"),
    "x is 42% 7 2.5 {\"a\":1} [ 1 ] extra { b: 2 }"
  );
  assert_eq!(log_line("console.log('100%% %s')"), "100%% %s");
  assert_eq!(log_line("console.log('%s and %s', 'one')"), "one and %s");
}

#[test]
fn a_value_that_throws_while_inspected_prints_a_placeholder() {
  assert_eq!(
    log_line("console.log(new Proxy({}, { ownKeys() { throw new Error('nope') } }), 'after')"),
    "[unprintable value] after"
  );
}
