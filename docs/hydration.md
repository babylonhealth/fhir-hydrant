# Hydration

Hydration is the process of filling in a template. Taking _dehydrated FHIR_ (or _dfhir_ for short) and turning it into FHIR.
For this, we require the `Hydration` class. You can initialise it with your definitions:
```scala
import com.emed.hydrant.Hydration

val hydration = Hydration(definitions)
```
Then given a `Template` and some dfhir (`io.circe.Json`), we can hydrate the dfhir using the template:
```scala
val dfhir: Json = ???
val template: Template = ???
hydration.hydrateJson(template)(dfhir)
```
This returns an `Either` containing an error when something went wrong or a `io.circe.Json` on success.
If you want to throw the error instead, you can use:
```scala
hydration.hydrateUnsafe(template, dfhir)
```

The `Hydration` class has various other parameters in its constructor:
```scala
class Hydration(val definitions: Definitions, redactObjects: Set[TemplateJson.Obj] = Set.empty)(using
    typeProvider: FhirTypeProvider, referenceProvider: ReferenceProvider)
```
These control features of hydration which you may want to customise.


### redactObjects
This can be used when undesirable json objects appear in the hydrated FHIR.

For example, suppose you have a template:
```
{
  ...
  "params": {
    "code": {"type": "string", "optional": true, ...},
  },
  "hydrated": {
    "code": {
      "coding": [
        {
          "system": "https://bbl.health",
          "code": "{{{code}}}"
        }
      ]
    },
    ...
  }
}
```
Then if the code is not present in the dfhir, then because the `coding.code` field is optional in the FHIR spec, we will end up with:
```
{
  "code": {
    "coding": [
      {
        "system": "https://bbl.health"
      }
    ]
  },
  ...
}
```
But in that case, probably we do not want a `coding` or indeed `code` at all. So we can specify that we never want to see the object:
```
{
  "system": "https://bbl.health"
}
```
To do this we use the `redactObjects` parameter.
```scala
import com.emed.hydrant.TemplateJson
import com.emed.hydrant.TemplateString

val redact = Set(TemplateJson.Obj("system" -> TemplateString("https://bbl.health")))

val hydration = Hydration(definitions, redactObjects = redact)
```
Then when `code` is not present in dfhir, this object will be redacted, in turn `coding` in the hydrated FHIR will be an empty array and hence redacted. And `code` in hydrated FHIR will be an empty object, and hence also redacted - as we wanted.

### FhirTypeProvider

For Hydration we need an implicit `FhirTypeProvider` in scope.
This class provides information about FHIR types, and for that it needs to read hl7 structure definitions for all of the base resource types.
By default this will be from the classpath, it will look for json files in a folder called `profiles`.
You can however override with your own implementation and load them from a file or a different classpath folder.

### ReferenceProvider

For Hydration we also need an implicit `ReferenceProvider` in scope.
Hydrant allows you to add inline resources. When it does this, in place of the full resource will be a reference.
In order to make this reference, we need to be able to take a FHIR resource and write a reference for it.
This is the main functionality given by the `ReferenceProvider`.

By default, the `id` and `resourceType` fields are taken from the referenced resource and the reference looks like:
```json
{
    "reference": "<resourceType>/<id>"
}
```
This is a simple compliant way for most FHIR servers.

However you may want to use the `identifer` field in the referenced resource, and look for a specific identifier system.
You can use the following:
```scala
given ReferenceProvider = IdentifierBasedReferenceProvider.fromPattern((domain, resourceType) => s"http://$domain.example.com/$resourceType")
```

Then generated references for inline resources would look like:
```json
{
    "reference": "http://<domain>.example.com/<resourceType>/<id>"
}
```
Where `<domain>` is the domain from the template, `<resourceType>` is the resource type of the referenced resource, and `<id>` is the id field.

Reference providers also allow automatically giving an `identifier` to resources where this is valid, by overriding the `identifierSystem` method.
When the resource type permits it, the `IdentifierBasedReferenceProvider` in the previous example automatically adds an identifier:
```json
{
  "system": "http://<domain>.example.com/<resourceType>",
  "value": "<id>"
}
```

There are more methods on the `ReferenceProvider`, but these are only used by [profile generation](https://github.com/babylonhealth/fhir-hydrant/blob/main/docs/profilegen.md#ReferenceProvider).