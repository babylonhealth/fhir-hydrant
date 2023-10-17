### FHIR Conformance Resource (Profile) Generation

Templates give us the types and cardinalities of input dfhir, as well as the shape of the output FHIR.
So we can work out the range of possible values of our hydrated FHIR and describe it using a FHIR profile.
Enums can also give us value sets and if we have included any extensions we know a bit about those too so we can generate an extension definition.


The `ProfileGen` class has a few options in its constructor.
```scala
class ProfileGen(override val hydrate: Hydration, genGroupedProfiles: Boolean = false)(using
    override val idProvider: IdProvider,
    referenceProvider: ReferenceProvider,
    paths: OutputPaths,
    override val log: Logs,
    override val structureDefinitions: StructureDefinitions)
```

### Hydration
A `Hydration` class is required. This is because internally profileGen uses hydration.
The options for this can be configured as in [hydration docs](https://github.com/babylonhealth/fhir-hydrant/blob/main/docs/hydration.md).
`ProfileGen` also uses the `Definitions` class used to construct `Hydration` to hold its definitions.

### genGroupedProfiles
When using child templates, you may want to use groupings to group your templates together with similar properties.
E.g. say you have the abstract template `BodyMeasurements` with the following child templates implementing it:
- `HeightInCentimetres`
- `HeightInInches`
- `WeightInKilograms`
- `WeightInPounds`
With the groups `Height` and `Weight`.

By default, `ProfileGen` will only generate profiles for the `Height` and `Weight` grouped level.
For height it will use a value set for the `Centimetres` and `Inches`.
If you want a full profile for every child template too, you can specify `genGroupedProfiles=true`.

```scala
val profileGen = ProfileGen(hydration, genGroupedProfiles = true)
```

### idProvider
When generating conformance resources, we need to generate various ids and urls for the resources.
The `IdProvider` class tells profile generation how to generate these.
While there are some default implementations, you can override methods as required.

The easiest way to create this is simply with your own base url:
```scala
given IdProvider = IdProvider("http://example.com")
```
With this `IdProvider`, a generated profile from the template with id `TemplateId` will have `url` equal to `http://example.com/TemplateId`.

You may want to transform the template ids to for example to add a prefix. For that you can override the method `transformId` in your `IdProvider`:
```scala
given IdProvider = new IdProvider {
  override lazy val baseUrl = "http://example.com"
  override def transformId(templateId: String) = s"Bbl$templateId"
}
```
Then the url would be `http://example.com/BblTemplateId`. This also applies to generated value sets.
If you want, you can override each method directly:
```scala
given IdProvider = new IdProvider {
  override lazy val baseUrl = "http://example.com"
  override def profileUrlFromId(id: String): String = s"$profileUrlBase/Profiles/$id"
  override def valueSetUrlFromId(id: String): String = s"$profileUrlBase/ValueSets/$id"
}
```
There are various similar methods which you can override for the `name` of the conformance resources.
By default they just drop the `domain` from the start of the `id` if it is there. 
```scala
def nameFromIdAndDomain(id: String, domain: String): String          = dropIfInit(id, domain)
def extensionNameFromIdAndDomain(id: String, domain: String): String = nameFromIdAndDomain(id, domain)
def profileNameFromIdAndDomain(id: String, domain: String): String   = nameFromIdAndDomain(id, domain)
def valueSetNameFromIdAndDomain(id: String, domain: String): String  = nameFromIdAndDomain(id, domain)
```

If you would like to attach some metadata to the generated conformance resources, you can do so by overriding the following:
```scala
lazy val profileMeta: Option[Meta]   = None
lazy val extensionMeta: Option[Meta] = None
lazy val valueSetMeta: Option[Meta]  = None
```
For example you can specify a profile which your profile `StructureDefinitions` should follow:
```scala
import com.babylonhealth.lit.core.*
```
```scala
override lazy val profileMeta = Some(Meta(profile = LitSeq("https://fhir.bbl.health/StructureDefinition/BblStructureDefinition")))
```

If you want your profiles to inherit values from another profiles you can override `baseUrlByResourceType` as follows:
```scala
override def baseUrlByResourceType(resourceType: String, structureDefinitions: StructureDefinitions): Option[UriStr] =
resourceType match {
  case "Observation" => Some("https://fhir.bbl.health/StructureDefinition/BblObservation")
  case _             => structureDefinitions.hl7DefnByType.get(resourceType).map(_.url)
}
```
This method selects the base url to use for your profiles. The structure definition with corresponding url should alwaus exist in your [`StructureDefinitions` class](https://github.com/babylonhealth/fhir-hydrant/blob/main/docs/profilegen.md#StructureDefinitions).

At times we need a way of getting the template id from the url. If you have changed the id generation rules you may need to override
```scala
override def baseTemplateIdsFromUrl(baseUrl: String): Seq[String] = Seq(baseUrl.split('/').last)
```

### ReferenceProvider
Profile generation needs to understands how references will be made. For this it uses an implicit `ReferenceProvider`.
Some of the methods of this class [are also needed for hydration](https://github.com/babylonhealth/fhir-hydrant/blob/main/docs/hydration.md#ReferenceProvider).
But profile generation requires additional information from reference uris.

```scala
def resourceTypeFromReferenceUri(referenceUri: String): Option[String] // May need to override if you have a different implementation for reference uris.
def profilesFromReferenceUri(referenceUri: String): Seq[String] // Override to add additional profiles to references
```
These have default implementations, but you may need to override them depending on your reference uris.

### OutputPaths
The `ProfileGen` class has methods (e.g. `makeAllProfilesAndValueSets`) that outputs `ConformanceResourceFile` classes.
These consist of a `DomainResource` (holding the `StructureDefinition` or `ValueSet`) and a `Path` where it should be written.

The implicit `OutputPaths` class determines how this path is generated.
By default there will be three folders `profiles`, `valueSets` and `extensions` with the relevant resources.

You can call the `write()` method on a `ConformanceResourceFile` to write the file to its output path.

### Logs
This is an implicit logging trait. By default this will just use `println`.

### StructureDefinitions
This is an extended version of the [`FhirTypeProvider` trait](https://github.com/babylonhealth/fhir-hydrant/blob/main/docs/hydration.md#FhirTypeProvider) that parses into the Lit library (which is only used in profile generation).
This allows for more details of the base resource structure definitions to be used.
It also allows you to include your own profile structure definitions, which can be [inherited in the profile hierarchy](https://github.com/babylonhealth/fhir-hydrant/blob/main/docs/profilegen.md#) when generating.
By default it will load from the class path in a folder called `profiles`.