# Reference — Data Types

How OPC UA values map to the on-wire ggcommons JSON, in both directions. The adapter converts every
value through one codec (`ValueCodec`): **read** = OPC UA → JSON (`toSample`), **write** = JSON → OPC UA
(`variantFromValue`). The same rules apply to a subscribed `SouthboundTagUpdate` sample, an on-demand
read result, and a write request.

The "KEP type" column gives the KEPServerEX tag data type (and its Configuration-API enum code) that
produces each OPC UA type, for convenience when modelling a Kepware project. The mapping itself is
generic OPC UA and applies to any server.

## Supported types (full round-trip)

These read cleanly and can be written back.

| OPC UA type | KEP data type (code) | Read — on-wire JSON | Write — accepted JSON |
|-------------|----------------------|---------------------|-----------------------|
| `Boolean`   | Boolean (1)          | boolean             | boolean               |
| `SByte`     | Char (2)             | number (−128…127)   | number (integer)      |
| `Byte`      | Byte (3)             | number (0…255)      | number (integer)      |
| `Int16`     | Short (4)            | number              | number (integer)      |
| `UInt16`    | Word (5)             | number (≥ 0)        | number (integer)      |
| `Int32`     | Long (6)             | number              | number (integer)      |
| `UInt32`    | DWord (7)            | number (≥ 0)        | number (integer)      |
| `Int64`     | LLong (13)           | number              | number (integer)      |
| `UInt64`    | QWord (14)           | number (≥ 0)        | number (integer)      |
| `Float`     | Float (8)            | number              | number                |
| `Double`    | Double (9)           | number              | number                |
| `String`    | String (0 / 10)      | string              | string                |
| `DateTime`  | Date (15) ¹          | string — ISO-8601 UTC (e.g. `2030-06-15T08:09:10Z`) | string — ISO-8601 |
| `<T>[]` (array of any row above) | `<T>Array` (scalar code + 20, e.g. WordArray = 25) | array — each element by its row's rule | array — coerced element-wise; length must match the tag's array dimension |

¹ KEPServerEX's **Simulator** driver does not offer the Date type, so KEP cannot host a writable
DateTime tag; the DateTime round-trip is validated against the asyncua simulator instead. Other
drivers/servers that expose `DateTime` work normally.

### Arrays

An array tag (OPC UA `ValueRank` ≥ 1) is carried as a **JSON array**, each element rendered by its
scalar rule above — e.g. a `UInt16[4]` reads as `[1, 2, 3, 65000]`, a `String[]` as
`["a","b","c"]`, a `DateTime[]` as an array of ISO strings. To **write** an array, send a JSON array
as `value`; elements are coerced to the node's element type and the array length must equal the tag's
declared dimension.

## Pass-through types (read as string, not writable)

Values the contract does not model as a number/boolean/string/array/datetime fall back to their string
form on read and are **rejected on write** (the entry is skipped with a logged warning; the rest of a
batch proceeds). Key on `tag.id` / `tag.address` for the native handle if you need to interpret them.

| OPC UA type | Read — on-wire JSON | Write |
|-------------|---------------------|-------|
| `ByteString`                | string (opaque)             | ✗ |
| `Guid`                      | string (UUID)               | ✗ |
| `XmlElement`                | string                      | ✗ |
| `NodeId` / `ExpandedNodeId` | string                      | ✗ |
| `QualifiedName` / `LocalizedText` | string                | ✗ |
| `StatusCode`                | string                      | ✗ |
| Structure (`ExtensionObject`) | string (debug form)       | ✗ |
| `Variant` / `DataValue` / `DiagnosticInfo` | string       | ✗ |

## Notes

- **Number precision.** Integers are emitted as JSON numbers across the full 64-bit range. Consumers
  whose JSON parser uses IEEE-754 doubles (e.g. JavaScript `JSON.parse`) may lose precision for
  `|value| > 2^53`; parse such tags as big integers if exactness matters.
- **Unsigned values.** `Byte`, `UInt16`, `UInt32`, `UInt64` are emitted as their non-negative value.
- **`null` values.** A node with no value yields `"value": null`.
- **Quality, not exceptions.** An unreadable or unknown node returns an entry with `quality` `BAD`
  (not an error); see the [sample object](messaging-interface.md#sample-object) and
  [quality](messaging-interface.md#southbound_health-metric). Type coercion that fails on write skips
  only that entry.
- **KEP BCD / LBCD (codes 11 / 12).** These are KEPServerEX register *presentations*, exposed over
  OPC UA as their underlying numeric type (typically `UInt16` / `UInt32`); they ride the matching
  numeric row above.
- **Where this lives.** Read mapping: `ValueCodec.toSample` / `encodeValue`. Write mapping:
  `ValueCodec.variantFromValue` / `arrayVariant`.
