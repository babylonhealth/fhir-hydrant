# Dehydration

Deydration is the process extracting fields from a FHIR resource given a template. It is the reverse of hydration turning FHIR into dfhir.
For this, we require the `Deydration` class. You can initialise it with your definitions:
```scala
import com.emed.hydrant.Deydration

val dehydration = Deydration(definitions)
```

Then given a `Template` and a FHIR resource (parsed as `io.circe.Json`), we can dehydrate the dfhir using the template:
```scala
val fhir: Json = ???
val template: Template = ???
dehydration.dehydrate(template)(fhir)
```
This returns an `Either` containing an error when something went wrong or a `io.circe.Json` on success.
On success, the resulting json will follow the schema of the template `params`.
If you want to throw the error instead, you can use:
```scala
hydration.dehydrateUnsafe(template, dfhir)
```

The `Deydration` class has various other parameters in its constructor:
```scala
class Dehydration(definitions: Definitions,
                  disambiguationStrategy: DisambiguationStrategy = Strict,
                  strictFullListDehydrate: Boolean = true,
                  checkNonOptional: Boolean = true)(using FhirTypeProvider)
```
These control features of hydration which you may want to customise.

### DisambiguationStrategy
In the process of dehydration, it is not always clear which values to take when lists of objects are involved.

If we have a template with hydration section including the following:
```json
{
    ...
    "code": {
        "coding": [
            {
                "system": "https://bbl.health",
                "code": "{{{bblCode}}}"
            },
            "{{{otherCodings}}}"
        ]
    }
}
```
where `otherCodings` has type hydrating to:
```json
{
    "system": "{{{system}}}",
    "code": "{{{code}}}"
}
```

Then when dehydrating, this could be ambiguous. What do we do with:
```json
{
    ...
    "code": {
        "coding": [
            {
                "system": "https://bbl.health",
                "code": "654321"
            }
            {
                "system": "https://bbl.health",
                "code": "123456"
            }
        ]
    }
}
```
Should they both be `otherCodings`, should one be the `bblCode`, if so which one?

The `disambiguationStrategy` lets you pick the behaviour in cases like these. There are currently three options:

- `Strict`
- `PathIgnore(paths: Set[String])`
- `Order`

`Strict` means that there must be some fixed value distinguishing cases in the list.
So the above template would not work if the `system` param for the codings was a string.
However if it is an enum with no option for `https://bbl.health` then it would disambiguate happily.
Somewhere within each option needs to be some fixed value determining that allows disambiguation.

If our template had: 

```json
{
    "system": "https://bbl.health",
    "code": "{{{bblCode}}}",
    "display": "bblCode"
}

```
but our FHIR had:
```json
{
    "system": "https://bbl.health",
    "code": "123456",
    "display": "Malaria"
}
```
Then `Strict` would not dehydrate this.
But if we wanted it to succeed we could use a `disambiguationStrategy` of `Pathignore(Set("Observation.code.coding.display"))`.
This would ignore the `display` field when disambiguating and happily dehydrate `bblCode` with a value of `"123456"`.

The last strategy is `Order`. This means that lists must match the order of the template.
The first element of the list will be dehydrated with the first element in the list of the template.
Repeated elements should come last in the list in your template and all remaining elements will be dehydrated with that.

For example:
```scala
import com.emed.hydrant.Order
val dehydration = Deydration(definitions, disambiguationStrategy = Order)
```

### `structFullListDehydrate`

Suppose your FHIR resource has:
```json
{
    ...
    "code": {
        "coding": [
            {
                "system": "https://bbl.health",
                "code": "654321"
            }
            {
                "system": "https://bbl.health",
                "code": "123456"
            }
        ]
    }
}
```
But your template only has:
```json
{   
    "params": {
        "bblCode": {"type": "string"}
    }
    ...
    "code": {
        "coding": [
            {
                "system": "https://bbl.health",
                "code": "{{{bblCode}}}"
            }
        ]
    }
}
```
Because `bblCode` is not repeated, not all of the codes in our FHIR resource can be extracted.
This means that dehydrating will drop data. When this is a problem set `structFullListDehydrate` to `true`.
```scala
val dehydration = Deydration(definitions, structFullListDehydrate=true)
```

### `checkNonOptional`
Suppose you have the template
```json
{   
    "params": {
        "bblCode": {"type": "string"},
        "patientId": {"type": "string"}
    }
    ...
    "code": {
        "coding": [
            {
                "system": "https://bbl.health",
                "code": "{{{bblCode}}}"
            }
        ]
    },
    "patient": {"reference": "Patient/{{{patientId}}}"}
}
```
but your FHIR is just:
```json
{
    "patient": {"reference": "Patient/1234567890"}
}
```
The best that dehydration can do is to extract:
```json
{
    "patientId": "1234567890"
}
```
However strictly speaking this is invalid dfhir, since `bblCode` is not present and in the template that parameter did not have `optional` set to `true`.

By default this will cause dehydration to error.
However if you still want to extract what you can, you can set `checkNonOptional=false`
```scala
val dehydration = Deydration(definitions, checkNonOptional=false)
```

### FhirTypeProvider

As with the `Hydration` class, the `Dehydration` class needs an implicit `FhirTypeProvider` in scope.
This class provides information about FHIR types, and for that it needs to read hl7 structure definitions for all of the base resource types.
By default this will be from the classpath, it will look for json files in a folder called `profiles`.
You can however override with your own implementation and load them from a file or a different classpath folder.