# Writing Templates

Templates consist of three parts:
* [Metadata](#metadata) 
    * id
    * domain (who owns the template)
    * name and description for corresponding FHIR profile
* [Params](#params)
    * Defines input (the dfhir format)
* [Hydrated mapping](#hydrated-mapping)
    * Mapping from inputs into fhir
    * Json FHIR resource, with 'holes'

---
### Example Template

```json
{
  "id": "BodyWeight",
  "name": "Body weight",
  "domain": "testing",
  "description": "A body weight measurement in lbs",
  "params": {
    "patientId": {"type": "uuid", "description": "patient id", "optional": true, "tags": {"pii": true}},
    "clinicianId": {"type": "uuid", "description": "clinician id", repeated": true},
    "value": {"type": "integer", "description": "body weight in lbs", "tags": {"spii": true, "other_numbers": true}},
    "timestamp": {"type": "dateTime", "description": "time the measurement was taken", "tags": {"redact_as_date": "label=.*fail.*"}}
  },
  "hydrated": {
    "resourceType": "Observation",
    "status": "final",
    "code": {
      "coding": [
        {
          "system": "https://bbl.health",
          "code": "ykWNn2DwyB"
        }
      ]
    },
    "subject": {
      "reference": "Patient/{{{patientId}}}"
    },
    "performer": [
      {
        "reference": "Practitioner/{{{clinicianId}}}"
      }
    ],
    "effectiveDateTime": "{{{timestamp}}}",
    "valueQuantity": {
      "value": "{{{value}}}",
      "unit": "lbs",
      "system": "http://unitsofmeasure.org",
      "code": "[lb_av]"
    }
  }
}
```

---    

## Metadata

The following fields are required on every template:
* `id` - Unique identifier for the template. The field should be written in camel case format, and be case-insensitive unique.
* `name` - Name of the template (used in FHIR profile)
* `domain` - Owner of the template
* `description` - Short description of what the message means

These are optional fields
* `abstract` - Set to `true` if child templates should be used as subtypes of this template
* `baseDefinition` - Base FHIR profile to use for profile generation. If left blank it will use the base resource type definition. Or the parent template profile (See [Template Polymorphism](#template-polymorphism)) or the group profile defined below:
* `group` - If the template is a child template (See [Template Polymorphism](#template-polymorphism)), child templates with the same entry for this field indicates will be grouped for profile and value set generation. By default individual profiles for grouped child templates will not be generated (unless `genGroupedProfiles` is set to `true`).

## Params

Params define the input to the template. This is the dfhir schema.

`params` is a json object, each key is the name of a param, and the value is a `paramInfo` object with fields:
* `type` - the type of this input, either a [FHIR primitive](https://build.fhir.org/datatypes.html), the id of an Enum, or the id of another Template.
* `description` - Brief description of what the parameter means. Becomes the comment in generated proto.
* `repeated` - Optional flag (default `false`). This means that we expect multiple inputs of this parameter. I.e. expects a list in json. Repeated fields are always treated as optional too.
* `optional` - Optional flag (default `false`). If set to `true` then the input parameter may or may not be present. 
* `tags` - Optional tags for marking fields.
* `provided` - Optional flag for propagating, see [below](#propagating-parameters) for details.
* `flatten` - Optional flag for nested dfhir definition. See [below](#template-flattening) for details.
* `abstract` - Optional flag for template inheritance. See [below](#template-polymorphism) for details. This flag is mutually exclusive with `flatten`, `provided`, `tags`
* `childTypeFieldNumber` - Optional field for template inheritance. See [Template Polymorphism](#template-polymorphism) for details.
* `contained` - Optional flag to make a [contained resource](#contained-resources).
#### Example

Template params:
```json
"params": {
    "patientId": {"type": "uuid", "description": "patient id", "optional": true, "tags": {"pii": true}},
    "clinicianId": {"type": "uuid", "description": "clinician id", "repeated": true},
    "value": {"type": "integer", "description": "body weight in lbs", "tags": {"spii": true, "other_numbers": true}},
    "timestamp": {"type": "dateTime", "description": "time the measurement was taken", "tags": {"redact_as_date": "label=.*fail.*"}}
  }
```
Corresponding dfhir input:
```json
{
    "patientId": "123e4567-e89b-12d3-a456-426614174000",
    "clinicianId": ["123e4567-e89b-12d3-a456-426614174001", "123e4567-e89b-12d3-a456-426614174002"],
    "value": 300,
    "timestamp": "2019-11-01T12:41:50+00:00"
}
```

Sometimes it is necessary to reference another template as the type of a parameter. 
Usually this happens when there are repeated parameters, each containing multiple values.
```json
{
    "id": "ExampleTemplate",
    "params": {
        "codes": {"type": "ReferencedTemplate", "repeated": true, ...},
    },
    ... 
}
{
    "id": "ReferencedTemplate",
    "params": {
        "system": {"type": "string", "description": "code system"},
        "code": {"type": "string", "description": "code value"}
    },
    ... 
}
```
Corresponding dfhir input:
```json
{
    "codes": [ 
        { "system": "https://bbl.health", "code": "abd456789" },
        { "system": "https://bbl.health", "code": "wTf420bBq" },
        { "system": "http://snomed.org", "code": "12348573" }
    ]
}
```

--- 

## Hydrated mapping
This consists of a FHIR resource, or part of a FHIR resource, with template tokens corresponding to each of the input parameters.
The template tokens are json strings, containing the name of a parameter in three curly braces, for example: `{{{paramName}}}`.
When hydration occurs, each template token is replaced by the hydrated value of its corresponding parameter.
Any full FHIR resource template should contain a `resourceType` and `id` field.
References should begin with the resource type, followed by a `/` and the id of the referenced resource as expected by the [FHIR spec](https://www.hl7.org/fhir/references.html).
### Simple case
```json
{
  ...
  "params": {
    "id": {"type": "uuid", ...},
    "code": {"type": "string", ...},
    "patientId": {"type": "uuid", ...}
  },
  "hydrated": {
    "resourceType": "Observation",
    "status": "final",
    "id": "{{{id}}}"
    "code": {
      "coding": [
        {
          "system": "https://bbl.health",
          "code": "{{{code}}}"
        }
      ]
    },
    "subject": {
      "reference": "Patient/{{{patientId}}}"
    }
  }
}
```
Then, given the input dfhir (as defined with the `params` field above)
```json
{
    "id": "678e4567-e89b-12d3-a456-426614174200",
    "code": "abd456789",
    "patientId": "123e4567-e89b-12d3-a456-426614174000"
}
```
The hydrated output would be:
```json
{
  "resourceType": "Observation",
  "status": "final",
  "id": "678e4567-e89b-12d3-a456-426614174200",
  "code": {
    "coding": [
      {
        "system": "https://bbl.health",
        "code": "abd456789"
      }
    ]
  },
  "subject": {
    "reference": "Patient/123e4567-e89b-12d3-a456-426614174000"
  }
}
```
Specific values encoded in the template are always present and remain fixed.
These only need to be defined in the template and do not need to be provided in dfhir.

In the above example we notice that `string` typed values can be interpolated into strings in the hydrated output, resulting in the reference value `"Patient/123e4567-e89b-12d3-a456-426614174000"`.
This is also possible with [Enums](#enums)

---

### Repeated parameters
When a parameter is marked as `repeated`, the input is a list.
When hydration occurs, the inner-most json array containing the parameter's token is where the repetition occurs.
```json
{
    "id": "RepeatedValues",
    "params": {
        "codes": {"type": "string", "repeated": true, ...},
    },
    "hydrated": {
        "resourceType": "Observation",
        "category": [
            { 
                "coding": [
                    {"system": "https://bbl.health", "code": "{{{codes}}}"}
                ]
            } 
        ],
        ...
    },
    ... 
}
```
The above template, with dfhir input `{ "codes": ["code1", "code2", "code3"] }` gives hydrated fhir:
```json
{
    "id": "RepeatedValues",
    "params": {
        "codes": {"type": "string", "repeated": true, ...},
    },
    "hydrated": {
        "resourceType": "Observation",
        "category": [
            { 
                "coding": [ 
                    {"system": "https://bbl.health", "code": "code1"},
                    {"system": "https://bbl.health", "code": "code2"},
                    {"system": "https://bbl.health", "code": "code3"}
                ]
            } 
        ],
        ...
    },
    ... 
}
```

---

All repeated fields *must be* contained in an array. The following will result in an error:

```json
{
    "id": "RepeatedValuesINCORRECT",
    "params": {
        "notes": {"type": "string", "repeated": true, ...},
    },
    "hydrated": {
        "resourceType": "Observation",
        "note": "{{{notes}}}"           // Nowhere to repeat the notes!
        ...
    },
    ... 
}
```
Instead, `{{{notes}}}` should be contained in a json array.
```json
 {
     "id": "RepeatedValues",
     "params": {
         "notes": {"type": "string", "repeated": true, ...},
     },
     "hydrated": {
         "resourceType": "Observation",
         "note": [ "{{{notes}}}" ]         // This array will be filled
         ...
     },
     ... 
 }
 ```

---

Arrays should only contain a single template token. 
If multiple inputs are needed to populate inner elements, another template for the inner object.
For example the following input would result in an error.
```json
{  
    "id": "CategorisedObservation",
    "params": {
        "system": {"type": "string", "repeated": true, ...},
        "code": {"type": "string", "repeated": true, ...}
    },
    "hydrated": {
        "resourceType": "Observation",
        "category": [ 
            {
                "coding": [ 
                    {"system": "{{{system}}}", "code": "{{{code}}}"} // Error, both system and code are repeated in the same array, which system goes with which code?
                ]  
            } 
        ],  
        ...
    },
    ...
}
```
We can get around this error by using recursive hydration.

---

### Recursive hydration

If another template id is specified as a parameter's type, the hydration process happens recursively. Inner templates are hydrated first.
```json
{  
    "id": "CategorisedObservation",
    "params": {
        "categories": {"type": "Category", "repeated": true, ...}
    },
    "hydrated": {
        "resourceType": "Observation",
        "category": [ "{{{categories}}}" ],
        ...
    },
    ...
}

{
    "id": "Category",
    "params": {
        "system": {"type": "string", "description": "code system"},
        "code": {"type": "string", "description": "code value"}
    },
    "hydrated": {
        "coding": [
            {"system": "{{{system}}}", "code": "{{{code}}}"}
        ]
    }
    ... 
}
```
With the above templates, and input dfhir:
```json
{
    "categories": [
        {"system": "https://bbl.health", "code": "Ap1C4rD2"},
        {"system": "https://bbl.health", "code": "Bp1C4rD3"},
        {"system": "http://snomed.org", "code": "052095092"}
    ]
}
```
The hydrated output is:
```json
{
    "resourceType": "Observation",
    "category": [
        { "coding": [{"system": "https://bbl.health", "code": "Ap1C4rD2"}] },
        { "coding": [{"system": "https://bbl.health", "code": "Bp1C4rD3"}] },
        { "coding": [{"system": "http://snomed.org", "code": "052095092"}] }
    ],
    ...
}
```

---

## Enums

It is also possible to define enums. When used for codings these correspond to FHIR value sets.
These require similar metadata to templates, and a list of values.
These values represent the hydrated value of each of the enums - this is mandatory.
Any JSON can be used as the value, as long as it creates valid FHIR and is possible to dehydrate back to the original.
Enums are referenced as the type of a parameter in the exact same way as an inner template.
### Example

```json
{
  "id": "Enum",
  "name": "Enum",
  "domain": "enum",
  "description": "Some enum",
  "values": [
    { "value": "A" },
    { "value": "B" },
    { "value": "C" }
  ]
}
```

In a case like this where the values are strings, the dfhir input for this enum is automatically generated from the enum name. In this case the dfhir values are `"ENUM_A"`, `"ENUM_B"` and `"ENUM_C"`. These hydrate to `"A"`, `"B"` and `"C"` respectively.

---
### Enum names
You can override these default dfhir names by specifying your own names.
For example when the hydrated value is a code then the auto generated name may be meaningless.
In this case we can populate the `name` field.
When this is left blank, the protobuf generation will come up with a name from the `value` and enum `name`.
```json
{
    "id": "QuestionnaireCode",
    "values" : [
      { "name": "QUESTIONNAIRE_CODE_ADULT", "value": "ZfwTODyI-T" },
      { "name": "QUESTIONNAIRE_CODE_PAEDIATRIC", "value": "KXH00g_3OJ"},
      { "name": "QUESTIONNAIRE_CODE_PREGNANT", "value": "PNXu6OsHWH" }
    ],
    ...
},
```

It is necessary to fill in the `name` field when json is used for the value that is not a string.

---

### Absent values
If the enum parameter is marked as `optional`, then the absent value corresponds to no value.
But sometimes to make the hydrated value valid FHIR, we cannot simply leave the field blank.
In these cases we can set the flag `allowAbsent` to `false`.
Then we must also set a default, to be used as the hydrated value when absent.
We may also provide an `absentName`, which the protobuf generation will use for the absent value.
If `allowAbsent` is set to `false`, the protobuf field will be marked as deprecated.
It can technically still be used, but **should not be!**.

```json
{
    "id": "QuestionnaireCode",
    "name": "Health Assessment Questionnaire Code",
    "values" : [
      { "name": "QUESTIONNAIRE_CODE_ADULT", "value": "ZfwTODyI-T" },
      { "name": "QUESTIONNAIRE_CODE_PAEDIATRIC","value": "KXH00g_3OJ"},
      { "name": "QUESTIONNAIRE_CODE_PREGNANT","value": "PNXu6OsHWH" }
    ],
    "absentName": "QUESTIONNAIRE_CODE_ABSENT",
    "allowAbsent": false,
    "default": "ZfwTODyI-T",
    ...
},
```

---

### Value set generation
It is possible to generate value sets from enums *if*:
* The values are [FHIR Codings](https://www.hl7.org/fhir/datatypes.html#Coding), with a defined `code` and `system`.
* The values are strings and the value set has `system` defined.

It is also possible to specify the url of the generated value set otherwise this will be created from the `id`.
The fhir type of the enum can also be specified.

```json
{
    "id": "QuestionnaireCode",
    "values" : [
      { "name": "QUESTIONNAIRE_CODE_ADULT", "value": "ZfwTODyI-T" },
      { "name": "QUESTIONNAIRE_CODE_PAEDIATRIC","value": "KXH00g_3OJ"},
      { "name": "QUESTIONNAIRE_CODE_PREGNANT","value": "PNXu6OsHWH" }
    ],
    "system": "https://bbl.health",
    "fhirType": "code",
    "url": "https://fhir.bbl.health/ValueSet/BblHealthAssessmentQuestionnaireCode",
    ...
  },
```

---

## Multiple resources

Often an event should generate more than one FHIR resource. There are two ways of doing this.
* Inline resource references
    * Preferred method, use this if possible.
    * Put a full resource in the hydration template where FHIR expects a `Reference` or `canonical`.
* Array template 
    * Only use if inlining isn't possible.
    * Hydrated mapping contains an array of FHIR resources, instead of a single one.
    
---

### Inline resource references

Wherever the FHIR spec expects a `Reference` or `canonical` type we can instead put the full resource inline.
Hydrant will then build resources, and fill in the reference ids appropriately.

A new template should be created for this inline resource and referenced as the type of a parameter.
This allows a FHIR profile to be generated for the inline resource.

```json
{  
    "id": "ObservationWithEncounter",
    "params": {
        "id": {"type": "string", "description": "Observation id"},
        "encounter": {"type": "InlineEncounter", "description": "Encounter where this observation occured"}
    },
    "hydrated": {
        "resourceType": "Observation",
        "id": "{{{id}}}"
        "encounter": "{{{encounter}}}",
        ...
    },
    ...
}

{
    "id": "InlineEncounter",
    "params": {
        "encounterId": {"type": "uuid", "description": "Encounter id"},
        "status": {"type": "string", "description": "Encounter status"},
        "patientId": {"type": "uuid", "description": "Patient id"},
        "practitionerId": {"type": "uuid", "description": "Patient id"}
    },
    "hydrated": {
        "resourceType": "Encounter",
        "id": "{{{encounterId}}}",
        "status": "{{{status}}}",
        "participant": [
            { "individual": {"reference": "Patient/{{{patientId}}}" } },
            { "individual": {"reference": "Practitioner/{{{practitionerId}}}" } }
        ]
    }
    ... 
}
```
The above profiles with the dfhir input:
```json
{
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "encounter": {
        "encounterId": "123e4567-e89b-12d3-a456-426614174001",
        "status": "finished",
        "patientId": "123e4567-e89b-12d3-a456-426614174002",
        "practitionerId": "123e4567-e89b-12d3-a456-426614174003"
    }
}
```
is hydrated into:
```json
[
    {
        "resourceType": "Observation",
        "id": "123e4567-e89b-12d3-a456-426614174000"
        "encounter": { 
            "reference": "Encounter/123e4567-e89b-12d3-a456-426614174001"       // Reference is filled in with the resourceType and id of the inline resource
        },
        ...
    },
    {
        "resourceType": "Encounter",
        "id": "123e4567-e89b-12d3-a456-426614174001",
        "status": "finished",
        "participant": [
            { "individual": {"reference": "Patient/123e4567-e89b-12d3-a456-426614174002" } },
            { "individual": {"reference": "Practitioner/123e4567-e89b-12d3-a456-426614174003" } }
        ]
    }
]
```

---

### Array template

Sometimes inline resources may not be possible or appropriate for an event.
Multiple resources can also be created by having a top-level template whose hydrated mapping is an array of inner resources.
Each inner resource should have its own template.

```json
{  
    "id": "MultipleResources",
    "params": {
        "observation": {"type": "ObservationTemplate", "description": "Observation"},
        "diagnosticReport": {"type": "DiagnosticReportTemplate", "description": "Diagnostic report", "optional": true},
        "relatedPeople": {"type": "RelatedPersonTemplate", "description": "Relatives", "repeated": true}
    },
    "hydrated": [
        "{{{observation}}}",
        "{{{diagnosticReport}}}",
        "{{{relatedPeople}}}"
    ],
    ...
}
```

---

## Advanced Usage

### Propagating parameters
Sometimes values in inner templates are always the same as those in outer templates.
For example, an Observation and Encounter involving the same patient:
 
```json
{  
    "id": "ObservationWithEncounter",
    "params": {
        "id": {"type": "string", "description": "Encounter id"},
        "patientId": {"type": "uuid", "description": "Patient id"},    // Expects patient id input here
        "encounter": {"type": "InlineEncounter", ...}
    },
    "hydrated": {
        "resourceType": "Observation",
        "id": "{{{id}}}",
        "subject": {"reference": "Patient/{{{patientId}}}" },
        "encounter": "{{{encounter}}}",
        ...
    },
    ...
}

{
    "id": "InlineEncounter",
    "params": {
        "encounterId": {"type": "uuid", "description": "Encounter id"},
        "patientId": {"type": "uuid", "description": "Patient id"},       // Also expects patient id input here
        "practitionerId": {"type": "uuid", "description": "Patient id"}
    },
    "hydrated": {
        "resourceType": "Encounter",
        "id": "{{{encounterId}}}",
        "status": "finished",
        "participant": [
            { "individual": {"reference": "Patient/{{{patientId}}}" } },
            { "individual": {"reference": "Practitioner/{{{practitionerId}}}" } }
        ]
    }
    ... 
}
```
These profiles expect dfhir that looks like this:
```json
{
    "id": "123e4567-e89b-12d3-a456-426614174002",
    "patientId": "999e9999-e89b-12d3-a456-400000000000",  // Input patient id once here
    "encounter": {
        "encounterId": "123e4567-e89b-12d3-a456-426614174003",
        "patientId": "999e9999-e89b-12d3-a456-400000000000",    // and again here
        "practitionerId": "123e4567-e89b-12d3-a456-426614174004"
    }
}
```
Ideally we could avoid putting in `patientId` twice.
In order to do this:
* Both parameters must have the same name and `type`
* The inner parameter must have the flag `provided` set to `true`
* The `tags` field of both parameters must be equivalent

```json
{
    "id": "InlineEncounter",
    "params": {
        "encounterId": {"type": "uuid", "description": "Encounter id"},
        "patientId": {"type": "uuid", "description": "Patient id", "provided": true }, // Provided set to true
        "practitionerId": {"type": "uuid", "description": "Patient id"}
    },
    "hydrated": {
        "resourceType": "Encounter",
        "id": "{{{encounterId}}}",
        "status": "finished",
        "participant": [
            { "individual": {"reference": "Patient/{{{patientId}}}" } },
            { "individual": {"reference": "Practitioner/{{{practitionerId}}}" } }
        ]
    }
    ... 
}
```

This change means that we only have to specify the `patientId` once, and it will appear hydrated in both places.
```json
// dfhir
{
    "id": "123e4567-e89b-12d3-a456-426614174002",
    "patientId": "999e9999-e89b-12d3-a456-400000000000",
    "encounter": {
        "encounterId": "123e4567-e89b-12d3-a456-426614174003",
        "practitionerId": "123e4567-e89b-12d3-a456-426614174004"
    }
}

// hydrated
[
    {
        "resourceType": "Observation",
        "id": "123e4567-e89b-12d3-a456-426614174002",
        "subject": {"reference": "Patient/999e9999-e89b-12d3-a456-400000000000" },               // Patient id appears here
        "encounter": { 
            "reference": "Encounter/123e4567-e89b-12d3-a456-426614174003"
        },
        ...
    },
    {
        "resourceType": "Encounter",
        "id": "123e4567-e89b-12d3-a456-426614174003",
        "status": "finished",
        "participant": [
            { "individual": {"reference": "Patient/999e9999-e89b-12d3-a456-400000000000" } },     // Propagated patient id appears here
            { "individual": {"reference": "Practitioner/123e4567-e89b-12d3-a456-426614174004" } }
        ]
    }
]

```

### Template flattening
If there is more nesting than desired in the dfhir format, it is sometimes possible to flatten an object.

```json
{  
    "id": "MultipleResources",
    "params": {
        "observation": {"type": "ObservationTemplate", "flatten": true, ...}, // Set flatten flag to true
        "diagnosticReport": {"type": "DiagnosticReportTemplate", ...},
        "relatedPerson": {"type": "RelatedPersonTemplate", ...}
    },
    "hydrated": [
        "{{{observation}}}",
        "{{{diagnosticReport}}}",
        "{{{relatedPerson}}}"
    ],
    ...
}

{  
    "id": "ObservationTemplate",
    "params": {
        "id": {"type": "string", "description": ...},
        "encounter": {"type": "uuid", ...}
    },
    "hydrated": {
        "resourceType": "Observation",
        "id": "{{{id}}}"
        "encounter": { "reference": "Encounter/{{{encounter}}}" },
        ...
    },
    ...
}
```
This changes the dfhir format to:
```json
{
    "id": "123e4567-e89b-12d3-a456-426614174000",        // Flattened from ObservationTemplate 
    "encounter": "123e4567-e89b-12d3-a456-426614174001", // Flattened from ObservationTemplate
    "diagnosticReport": { ... },                         // DiagnosticReportTemplate dfhir
    "relatedPerson": { ... }                             // RelatedPersonTemplate dfhir
}
```

Flattening should be used sparingly and will not work if there are any param name conflicts after flattening.

### Template Polymorphism

It is sometimes useful to set a multiple fields at once with a single enum.
We can do this by setting multiple parameters as `abstract` and then using child templates to implement these abstract parameters.
In this way, you only need to select the required implementation template as a `type` enum to select different implementation param groups. Here is a specific example:

```json
// Parent template
{
  "id":"BodyMeasure",
  ...,
  "params": {
    ...,
    "value":{"type": "integer",...},
    "unitCode":{"type": "code", ..., "abstract": true},
    "unit":{"type": "string", ..., "abstract": true},
    "code":{"type": "code", ..., "abstract": true},
    "display":{"type": "string", ..., "abstract": true}
  },
  "hydrant":{
    ...
    "code": {
      "coding": [
        {
          "system": "https://bbl.health",
          "code": "{{{code}}}",
          "display": "{{{display}}}"
        }
      ]
    },
    "valueQuantity": {
      "value": "{{{value}}}",
      "unit": "{{{unit}}}",
      "system": "http://unitsofmeasure.org",
      "code": "{{{unitCode}}}"
    }
  }
}

// Child template 1
{
  "id": "BodyMeasureWeightInKG",
  "name": "Body Measure Weight in kg",
  "extends": "BodyMeasure",
  "domain": "testing",
  "description": "body measurement weight in kg",
  "default": true,
  "implements": {
    "code": "123456789",
    "display": "Weight",
    "unitCode": "kg",
    "unit": "kg"
  }
}

// Child template 2
{
  "id": "BodyMeasureHeightInM",
  ...,
  "order": 1,
  "extends": "BodyMeasure",
  "implement": {
    "code": "987654321",
    "display": "Height",
    "unitCode": "[m]",
    "unit": "m"
  }
}
```
If a param is marked as `abstract` then this template is abstract and needs inheritance. 
All the child templates are suggested to reside on the same file beside the parent template for easy inspection. 

The inheritance templates should have the field `extends` indicating the parent template. 
Their dfhir should include a field `type` whose value is the value of one of the child templates.

For example the dfhir value:
```json
{
  ...
  "value": 2,
  "type": "BodyMeasureHeightInM"
}
```
hydrated with template `BodyMeasure` would give:
```json
{
    ...
    "code": {
      "coding": [
        {
          "system": "https://bbl.health",
          "code": "987654321",
          "display": "Height"
        }
      ]
    },
    "valueQuantity": {
      "value": 2,
      "unit": "m",
      "system": "http://unitsofmeasure.org",
      "code": "[m]"
    }
  }
```


A default inheritance template can be annotated with the `default` field, which indicates the template enum default choice when none is provided. 

The child template also needs to specify fields like `name`/ `domain` / `description`. Currently hild 
templates do not support `param` and `hydrated` field override.

The `implement` field needs to have all `abstract` fields in parent template filled with values, except if param in parent 
template is `optional`. If the param is repeated, the implement field should be an array.

When generating FHIR profiles (see [Where to put template](#where-to-put-templates)), the generated profiles will maintain
the hierarchy of the template: the child template's profile `baseDefinition` field will point to the url of the parent template's profile.
If the `group` field in child template file is defined, there will also generate a "group" level profile which will bind a ValueSet
whoch contains all possible child template options (for code/codableconcept) in the same group. 

i.e:
```json
{
  ...,
  "code": {
    "coding": [
      {
        "system": "https://bbl.health",
        "code": "{{{code}}}",
        "display": "{{{display}}}"
      }
    ]
  }
}
```
If the `code` and `display` fields are abstract, a ValueSet contains all code/display in child templates will be generated automatically. It's not recommend
that the code is optional in this case. If the `code` section need to be optional, you could set up like following:
```json
{
  "params": {
    "code": {
      "type": "CodeEnum",
      // pointing to an EnumDefinition with all options
      "description": "category",
      "abstract": true,
      "optional": true
    }
  }
  "hydrated": {
    ...,
    "code": {
      "coding": [
        "{{{code}}}"
      ]
    }
  }
}
```


### Contained resources

We support FHIR [contained resources](https://www.hl7.org/fhir/references.html#contained) using the `contained` tag. 
These are resources contained inside a parent resource. Contained resources are dependent on their parent. They
do not have an independent identity, meaning they have no global `id` or `identifier` and cannot be referenced outside
their parent resource.

For example, given the template:

```json
[
  {
    "id": "RiskAssessment",
    "params": {
      "riskFactor": {
        "type": "RiskFactor",
        "contained": true
      }
    },
    "hydrated": {
      "resourceType": "RiskAssessment",
      "id": "foo",
      "basis": [
        "{{{riskFactor}}}"
      ]
    }
  },
  {
    "id": "RiskFactor",
    "params": {
      "code": {
        "type": "code"
      },
      "value": {
        "type": "string"
      }
    },
    "hydrated": {
      "resourceType": "Observation",
      "code": {
        "coding": [
          {
            "system": "https://bbl.health",
            "code": "{{{code}}}"
          }
        ]
      },
      "valueString": "{{{value}}}"
    }
  }
]
```

The following dFHIR:

```json
{
  "riskFactor": {"code": "smoking_status", "value": "smoker"}
}
```

Would hydrate into:

```json
{
  "resourceType": "RiskAssessment",
  "id": "foo",
  "basis": [{"reference": "#riskFactor.0"}],
  "contained": [{
    "resourceType": "Observation",
    "id": "riskFactor.0",
    "code": {
      "coding": [
        {
          "system": "https://bbl.health",
          "code": "smoking_status"
        }
      ]
    },
    "valueString": "smoker"
  }]
}
```

Contained resources are useful when it's not possible to properly identify a resource, for example if a referenced
resource hasn't been given an `id`.
