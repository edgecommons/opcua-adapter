# Reference — Data Types

How OPC UA values map to EdgeCommons message values, in both directions. The adapter converts every
value through one codec (`ValueCodec`): **read** = OPC UA → EdgeCommons sample value (`toSampleParts` /
`toSample`), **write** = JSON command value → OPC UA (`variantFromValue`). Subscribed
`SouthboundSignalUpdate` samples are published as EdgeCommons protobuf messages. Diagnostic JSON and
on-demand read results render the same values as JSON for inspection and command replies; write
requests still accept JSON command values before OPC UA coercion.

The "KEP type" column gives the KEPServerEX tag data type (and its Configuration-API enum code) that
produces each OPC UA type, for convenience when modelling a Kepware project. The mapping itself is
generic OPC UA and applies to any server.

## Supported types (full round-trip)

These read cleanly and can be written back.

| OPC UA type | KEP data type (code) | Read — protobuf `EcValue` | Diagnostic/read JSON | Write — accepted JSON |
|-------------|----------------------|---------------------------|----------------------|-----------------------|
| `Boolean`   | Boolean (1)          | `bool_value`              | boolean              | boolean               |
| `SByte`     | Char (2)             | `int_value`               | number (−128…127)    | number (integer)      |
| `Byte`      | Byte (3)             | `int_value` (non-negative) | number (0…255)      | number (integer)      |
| `Int16`     | Short (4)            | `int_value`               | number               | number (integer)      |
| `UInt16`    | Word (5)             | `int_value` (non-negative) | number (≥ 0)        | number (integer)      |
| `Int32`     | Long (6)             | `int_value`               | number               | number (integer)      |
| `UInt32`    | DWord (7)            | `int_value` (non-negative) | number (≥ 0)        | number (integer)      |
| `Int64`     | LLong (13)           | `int_value`               | number               | number (integer)      |
| `UInt64`    | QWord (14)           | `int_value` when it fits signed 64-bit | number (≥ 0) | number (integer)      |
| `Float`     | Float (8)            | `double_value`            | number               | number                |
| `Double`    | Double (9)           | `double_value`            | number               | number                |
| `String`    | String (0 / 10)      | `string_value`            | string               | string                |
| `DateTime`  | Date (15) ¹          | `string_value`            | string — ISO-8601 UTC (e.g. `2030-06-15T08:09:10Z`) | string — ISO-8601 |
| `<T>[]` (array of any row above) | `<T>Array` (scalar code + 20, e.g. WordArray = 25) | `list_value`; each element by its row's rule | array | array — coerced element-wise; length must match the signal's array dimension |

¹ KEPServerEX's **Simulator** driver does not offer the Date type, so KEP cannot host a writable
DateTime signal; the DateTime round-trip is validated against the asyncua simulator instead. Other
drivers/servers that expose `DateTime` work normally.

### Arrays

An array signal (OPC UA `ValueRank` ≥ 1) is carried on the protobuf wire as
`EcValue.list_value`, with each element encoded by its scalar rule above. Diagnostic JSON and
on-demand read replies render that list as a JSON array — e.g. a `UInt16[4]` reads as
`[1, 2, 3, 65000]`, a `String[]` as `["a","b","c"]`, a `DateTime[]` as an array of ISO strings. To
**write** an array, send a JSON array as `value`; elements are coerced to the node's element type and
the array length must equal the signal's declared dimension.

## Native byte-read types

These read cleanly but are not writable by the adapter's JSON command surface.

| OPC UA type | Read — protobuf `EcValue` | Diagnostic/read JSON | Write |
|-------------|--------------------------|----------------------|-------|
| `ByteString` | `bytes_value` (native bytes) | EdgeCommons binary marker (`_edgecommonsBinary`) | ✗ |

## Pass-through types (read as string, not writable)

Values the contract does not model as a number/boolean/string/array/datetime fall back to their string
form on read and are **rejected on write** (the entry is skipped with a logged warning; the rest of a
batch proceeds). Key on `signal.id` / `signal.address` for the native handle if you need to interpret them.
The string is a best-effort rendering — **do not parse it**; its exact form is implementation/version
dependent.

| OPC UA type | Read — protobuf `EcValue` | Diagnostic/read JSON example | Write |
|-------------|--------------------------|-------------------------------|-------|
| `Guid`                      | `string_value` | string — the UUID, e.g. `12345678-1234-5678-1234-567812345678` | ✗ |
| `NodeId` / `ExpandedNodeId` | `string_value` | string, e.g. `NodeId{ns=2, id=SomeTag}`          | ✗ |
| `LocalizedText`             | `string_value` | string, e.g. `LocalizedText[locale=null, text=hello]` | ✗ |
| `QualifiedName`             | `string_value` | string, e.g. `QualifiedName[namespaceIndex=2, name=qn]` | ✗ |
| `StatusCode`                | `string_value` | string, e.g. `StatusCode[name=Good, value=0x00000000, quality=good]` | ✗ |
| `XmlElement`                | `string_value` | string, e.g. `XmlElement[fragment=<a>x</a>]`     | ✗ |
| Structure (`ExtensionObject`) | `string_value` | string (debug form)                            | ✗ |
| `Variant` / `DataValue` / `DiagnosticInfo` | `string_value` | string                            | ✗ |

The scalar pass-through rows are **verified end-to-end** against asyncua
(`validation/validate_passthrough.py`): each reads as the string shown and a write attempt is skipped,
leaving the value unchanged. The `ExtensionObject` (structure) row is confirmed via KEP's
`_System.ServerStatus`.

## Notes

- **Wire vs JSON surfaces.** `SouthboundSignalUpdate` samples are serialized as EdgeCommons protobuf.
  JSON examples here are diagnostic/read projections or write command payloads.
- **Number precision.** Integer reads are exact on the protobuf wire when represented as
  `EcValue.int_value`; diagnostic JSON renders them as JSON numbers. Consumers whose JSON parser uses
  IEEE-754 doubles (e.g. JavaScript `JSON.parse`) may lose precision for `|value| > 2^53`; parse such
  projected values as big integers if exactness matters.
- **Unsigned values.** `Byte`, `UInt16`, `UInt32`, `UInt64` are represented as non-negative values.
  The protobuf schema has `uint_value`, but this Java adapter path currently emits OPC UA numeric
  read values through the JSON-native sample path, which the Java protobuf encoder stores as
  `int_value`; `UInt64` values must fit signed 64-bit range on this path.
- **`null` values.** A node with no value yields `EcValue.null_value`; diagnostic/read JSON renders
  `"value": null`.
- **Quality, not exceptions.** An unreadable or unknown node returns an entry with `quality` `BAD`
  (not an error); see the [sample object](messaging-interface.md#sample-object) and
  [quality](messaging-interface.md#southbound_health-metric). Type coercion that fails on write skips
  only that entry.
- **KEP BCD / LBCD (codes 11 / 12).** These are KEPServerEX register *presentations*, exposed over
  OPC UA as their underlying numeric type (typically `UInt16` / `UInt32`); they ride the matching
  numeric row above.
- **Where this lives.** Read mapping: `ValueCodec.toSample` / `encodeValue`. Write mapping:
  `ValueCodec.variantFromValue` / `arrayVariant`.
