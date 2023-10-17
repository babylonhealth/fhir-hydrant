package com.emed.hydrant

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import TestHelpers.{templates, *}
import io.circe.Json

import scala.util.matching.Regex

class HydrationTest extends AnyFreeSpec with Matchers {

  given FhirTypeProvider = TestTypeProvider.typeProvider
  given ReferenceProvider = IdentifierBasedReferenceProvider.fromPattern((domain, resourceType) => s"https://${domain.toLowerCase}.bbl.health/$resourceType")

  lazy val hydrate                                                     = Hydration(templates, redactObjects)
  inline def hydrateUnsafe(template: Template, dehydrated: Json): Json = hydrate.hydrateUnsafe(template, dehydrated)
  val template                                                         = TestHelpers.template("BodyWeight")
  val templateEnum                                                     = TestHelpers.template("CodingJson")
  val templateBody                                                     = TestHelpers.template("PatientMetricsBodyMeasure")
  val templateLegacy                                                   = TestHelpers.template("LegacyEncounter")

  def parseAndHydrate(dehydrated: String, template: Template = template) = hydrateUnsafe(template, parse(dehydrated))

  "Simple hydrate Observation" in {
    val dehydrated =
      """
          |{
          |		"patientId": "patient-123",
          |		"clinicianId": ["doctor-420"],
          |		"value": 69.0,
          |   "timestamp": "2020-05-21T17:21:32.12.123Z"
          |}
          |""".stripMargin
    val hydrated =
      """{
          |  "subject" : {
          |    "reference" : "https://administration.bbl.health/Patient/patient-123"
          |  },
          |  "valueQuantity" : {
          |    "value" : 69.0,
          |    "unit" : "lbs",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  },
          |  "resourceType" : "Observation",
          |  "code" : {
          |    "coding" : [
          |      {
          |        "system" : "https://bbl.health",
          |        "code" : "ykWNn2DwyB"
          |      }
          |    ]
          |  },
          |  "id" : "123-123-123",
          |  "status" : "final",
          |  "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |  "performer" : [
          |    {
          |      "reference" : "https://administration.bbl.health/Practitioner/doctor-420"
          |    }
          |  ],
          |  "identifier" : [
          |    {
          |      "value" : "123-123-123",
          |      "system" : "https://testing.bbl.health/Observation"
          |    }
          |  ]
          |}""".stripMargin
    parseAndHydrate(dehydrated) shouldEqual parse(hydrated)
  }

  "Missing optional fields" in {
    val dehydrated =
      """
          |{
          |   "timestamp": "2020-05-21T17:21:32.12.123Z",
          |   "value" : 420
          |}
          |""".stripMargin
    val hydrated =
      """{
          |  "valueQuantity" : {
          |    "value" : 420,
          |    "unit" : "lbs",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  },
          |  "resourceType" : "Observation",
          |  "code" : {
          |    "coding" : [
          |      {
          |        "system" : "https://bbl.health",
          |        "code" : "ykWNn2DwyB"
          |      }
          |    ]
          |  },
          |  "id" : "123-123-123",
          |  "status" : "final",
          |  "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |  "identifier" : [
          |    {
          |      "value" : "123-123-123",
          |      "system" : "https://testing.bbl.health/Observation"
          |    }
          |  ]
          |}""".stripMargin
    parseAndHydrate(dehydrated) shouldEqual parse(hydrated)
  }

  "List of References (Practitioners)" in {
    val dehydrated =
      """
          |{
          |		"patientId": "patient-123",
          |		"clinicianId": ["doctor-420", "doctor-69", "doctor-1337"],
          |		"value": 69.0,
          |   "timestamp": "2020-05-21T17:21:32.12.123Z"
          |}
          |""".stripMargin
    val hydrated =
      """{
          |  "subject" : {
          |    "reference" : "https://administration.bbl.health/Patient/patient-123"
          |  },
          |  "valueQuantity" : {
          |    "value" : 69.0,
          |    "unit" : "lbs",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  },
          |  "resourceType" : "Observation",
          |  "code" : {
          |    "coding" : [
          |      {
          |        "system" : "https://bbl.health",
          |        "code" : "ykWNn2DwyB"
          |      }
          |    ]
          |  },
          |  "id" : "123-123-123",
          |  "status" : "final",
          |  "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |  "performer" : [
          |    {
          |      "reference" : "https://administration.bbl.health/Practitioner/doctor-420"
          |    },
          |    {
          |      "reference" : "https://administration.bbl.health/Practitioner/doctor-69"
          |    },
          |    {
          |      "reference" : "https://administration.bbl.health/Practitioner/doctor-1337"
          |    }
          |  ],
          |  "identifier" : [
          |    {
          |      "value" : "123-123-123",
          |      "system" : "https://testing.bbl.health/Observation"
          |    }
          |  ]
          |}""".stripMargin
    parseAndHydrate(dehydrated) shouldEqual parse(hydrated)
  }

  "Nested type template" in {
    val dehydrated = """
        |{
        |		"patientId": "patient-123",
        |		"clinicianId": ["doctor-420"],
        |		"value": 69.0,
        |   "timestamp": "2020-05-21T17:21:32.12.123Z",
        |   "coding": [{"code":"CODING_1", "system":"https://bbl.health"}, {"code":"CODING_2", "system":"https://bbl.wat2"}]
        |}
        |""".stripMargin
    val hydrated =
      """{
          |  "subject" : {
          |    "reference" : "https://administration.bbl.health/Patient/patient-123"
          |  },
          |  "valueQuantity" : {
          |    "value" : 69.0,
          |    "unit" : "lbs",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  },
          |  "resourceType" : "Observation",
          |  "code" : {
          |    "coding" : [
          |      {
          |        "system" : "https://bbl.health",
          |        "code" : "420420",
          |        "display" : "lol"
          |      },
          |      {
          |        "system" : "https://bbl.wat2",
          |        "code" : "696969",
          |        "display" : "lol"
          |      }
          |    ]
          |  },
          |  "id" : "12345",
          |  "status" : "final",
          |  "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |  "performer" : [
          |    {
          |      "reference" : "https://administration.bbl.health/Practitioner/doctor-420"
          |    }
          |  ],
          |  "identifier" : [
          |    {
          |      "value" : "12345",
          |      "system" : "https://testing.bbl.health/Observation"
          |    }
          |  ]
          |}""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("NestedConcept")) shouldEqual parse(hydrated)
  }

  "Propagate params" in {
    val dehydrated =
      """
          |{
          |   "patientId": "patient-123",
          |   "propagate": { "system": "https://bbl.health", "code": "420"},
          |   "timestamp": "2020-05-21T17:21:32.12.123Z"
          |}
          |""".stripMargin
    val hydrated =
      """{
          |  "resourceType" : "Observation",
          |  "id" : "1234567890",
          |  "status" : "final",
          |  "code" : {
          |    "coding" : [
          |      {
          |        "system" : "https://bbl.health",
          |        "code" : "420",
          |        "display" : "patient-123"
          |      }
          |    ]
          |  },
          |  "subject" : {
          |    "reference" : "https://administration.bbl.health/Patient/patient-123"
          |  },
          |  "performer" : [
          |    {
          |      "reference" : "https://administration.bbl.health/Practitioner/8a5f6cae-64a3-4405-8908-c1568ecc2cb6"
          |    }
          |  ],
          |  "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |  "valueQuantity" : {
          |    "value" : 5,
          |    "unit" : "lbs",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  },
          |  "identifier" : [
          |    {
          |      "value" : "1234567890",
          |      "system" : "https://testing.bbl.health/Observation"
          |    }
          |  ]
          |}""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("Propagate")) shouldEqual parse(hydrated)
  }

  "Propagate params to resource" in {
    val dehydrated =
      """
         |{
         |   "id": "1",
         |   "subject": "2",
         |   "practitioner": "checkbase",
         |   "propagate": {
         |     "status": "PPRF"
         |   }
         |}
         |""".stripMargin
    val hydrated =
      """{
          |      "resourceType": "Encounter",
          |      "id": "1",
          |      "subject": {"reference":  "Patient/2"},
          |      "status": "planned",
          |      "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code":  "VR", "display": "virtual"},
          |      "participant":[
          |        {
          |          "individual": {"reference": "Practitioner/checkbase"},
          |          "type":  [{ "coding": [{
          |            "code": "PPRF",
          |            "system": "http://terminology.hl7.org/CodeSystem/v3-ParticipationType"
          |          }]}]
          |        }
          |      ],
          |      "identifier" : [
          |        {
          |          "value" : "1",
          |          "system" : "https://testing.bbl.health/Encounter"
          |        }
          |      ]
          |    }
          |""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("PropagateList")) shouldEqual parse(hydrated)
  }

  "Propagate optional param (present) to resource" in {
    val dehydrated =
      """
          |{
          |   "timestamp": "2020-05-21T17:21:32.12.123Z",
          |   "propagate": [{
          |     "code": "abcd"
          |   }]
          |}
          |""".stripMargin
    val hydrated =
      """
          |[
          |  {
          |    "resourceType" : "DiagnosticReport",
          |    "id" : "1234567890",
          |    "identifier" : [
          |      {
          |        "value" : "1234567890",
          |        "system" : "https://testing.bbl.health/DiagnosticReport"
          |      }
          |    ],
          |    "status" : "final",
          |    "code" : {
          |      "coding" : [
          |        {
          |          "system" : "https://bbl.health",
          |          "code" : "ykWNn2DwyB"
          |        }
          |      ]
          |    },
          |    "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |    "result" : [{
          |      "reference" : "https://testing.bbl.health/Observation/1234567890"
          |    }]
          |  },
          |  {
          |    "resourceType" : "Observation",
          |    "id" : "1234567890",
          |    "identifier" : [
          |      {
          |        "value" : "1234567890",
          |        "system" : "https://testing.bbl.health/Observation"
          |      }
          |    ],
          |    "status" : "final",
          |    "code" : {
          |      "coding" : [
          |        {
          |          "system" : "https://bbl.health",
          |          "code" : "abcd"
          |        }
          |      ]
          |    },
          |    "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z"
          |  }
          |]
          |""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("PropagateOptional")) shouldEqual parse(hydrated)
  }

  "Propagate optional param (not present) to resource" in {
    val dehydrated =
      """
          |{
          |   "propagate": [{
          |     "code": "abcd"
          |   }]
          |}
          |""".stripMargin
    val hydrated =
      """
          |[
          |  {
          |    "resourceType" : "DiagnosticReport",
          |    "id" : "1234567890",
          |    "identifier" : [
          |      {
          |        "value" : "1234567890",
          |        "system" : "https://testing.bbl.health/DiagnosticReport"
          |      }
          |    ],
          |    "status" : "final",
          |    "code" : {
          |      "coding" : [
          |        {
          |          "system" : "https://bbl.health",
          |          "code" : "ykWNn2DwyB"
          |        }
          |      ]
          |    },
          |    "result" : [{
          |      "reference" : "https://testing.bbl.health/Observation/1234567890"
          |    }]
          |  },
          |  {
          |    "resourceType" : "Observation",
          |    "id" : "1234567890",
          |    "identifier" : [
          |      {
          |        "value" : "1234567890",
          |        "system" : "https://testing.bbl.health/Observation"
          |      }
          |    ],
          |    "status" : "final",
          |    "code" : {
          |      "coding" : [
          |        {
          |          "system" : "https://bbl.health",
          |          "code" : "abcd"
          |        }
          |      ]
          |    }
          |  }
          |]
          |""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("PropagateOptional")) shouldEqual parse(hydrated)
  }

  "Hydrate enum with json values" in {
    val dehydrated =
      """
          |{
          |   "status": "OBSERVATION_STATUS_ENUM_AMENDED",
          |   "code": "CODING_2",
          |   "valueQuantity": "OBSERVATION_VALUE_QUANTITY_HIGH",
          |   "bodySite": "OBSERVATION_BODY_SITE_HEAD_AND_NECK"
          |
          |}
          |""".stripMargin

    val hydrated =
      """{
          |  "id" : "1",
          |  "resourceType" : "Observation",
          |  "status" : "amended",
          |  "code" : {
          |    "code" : "696969",
          |    "display" : "cheeky",
          |    "system" : "https://bbl.health"
          |  },
          |  "valueQuantity" : {
          |    "value" : 10,
          |    "code" : "kg",
          |    "system" : "http://unitsofmeasure.org"
          |  },
          |  "bodySite" : {
          |    "coding" : [
          |      {
          |        "code" : "774007",
          |        "system" : "http://snomed.info/sct"
          |      }
          |    ]
          |  },
          |  "identifier" : [
          |    {
          |      "value" : "1",
          |      "system" : "https://testing.bbl.health/Observation"
          |    }
          |  ]
          |}""".stripMargin
    hydrateUnsafe(templateEnum, parse(dehydrated)) shouldEqual parse(hydrated)
  }

  "Hydrate data with inheritance template" in {
    val dehydrated =
      """{
          |   "value": 2,
          |   "type": "PATIENT_METRICS_BODY_MEASURE_CHILD_TEMPLATE_HEIGHT_IN_M"
          |}
          |""".stripMargin
    val hydrated =
      """{
          |     "id" : "1",
          |     "resourceType" : "Observation",
          |     "status" : "final",
          |     "code" : {
          |       "coding" : [
          |         {
          |           "system" : "https://bbl.health",
          |           "code" : "D_ViDKoBWF",
          |           "display" : "Body height"
          |         }
          |       ]
          |     },
          |     "valueQuantity" : {
          |       "value" : 2,
          |       "unit" : "[m]",
          |       "system" : "http://unitsofmeasure.org",
          |       "code" : "m"
          |     },
          |     "interpretation" : [
          |     {
          |      "coding" : [
          |        {
          |          "code" : "9rTncufFB3",
          |          "display" : "Exercise physically impossible",
          |          "system" : "https://bbl.health"
          |        }
          |      ]
          |    }
          |     ],
          |     "identifier" : [
          |       {
          |         "value" : "1",
          |         "system" : "https://testing.bbl.health/Observation"
          |       }
          |     ],
          |     "referenceRange" : [
          |       {
          |         "low" : {
          |         "value" : 1.50,
          |         "code" : "m",
          |         "unit" : "[m]",
          |         "system" : "http://unitsofmeasure.org"
          |       },
          |       "high" : {
          |         "value" : 1.94,
          |         "code" : "m",
          |         "unit" : "[m]",
          |         "system" : "http://unitsofmeasure.org"
          |       },
          |       "text" : "normal"
          |     },
          |     {
          |       "low" : {
          |         "value" : 0.00,
          |         "code" : "m",
          |         "unit" : "[m]",
          |         "system" : "http://unitsofmeasure.org"
          |       },
          |       "high" : {
          |         "value" : 0.01,
          |         "code" : "m",
          |         "unit" : "[m]",
          |         "system" : "http://unitsofmeasure.org"
          |       },
          |       "text" : "possibly an ant?"
          |     }
          |   ]
          | }
          |""".stripMargin
    hydrateUnsafe(templateBody, parse(dehydrated)) shouldEqual parse(hydrated)
  }

  "Hydrate data with inheritance template with default" in {
    val dehydrated =
      """{
          |   "value": 2
          |}
          |""".stripMargin
    val hydrated =
      """{
          |     "id" : "1",
          |     "resourceType" : "Observation",
          |     "status" : "final",
          |     "code" : {
          |       "coding" : [
          |         {
          |           "system" : "https://bbl.health",
          |           "code" : "ykWNn2DwyB",
          |           "display" : "Body weight"
          |         }
          |       ]
          |     },
          |     "interpretation" : [
          |    {
          |      "coding" : [
          |        {
          |          "code" : "9rTncufFB3",
          |          "display" : "Exercise physically impossible",
          |          "system" : "https://bbl.health"
          |        }
          |      ]
          |    }
          |  ],
          |     "valueQuantity" : {
          |       "value" : 2,
          |       "unit" : "kg",
          |       "system" : "http://unitsofmeasure.org",
          |       "code" : "kg"
          |     },
          |     "identifier" : [
          |       {
          |         "value" : "1",
          |         "system" : "https://testing.bbl.health/Observation"
          |       }
          |     ]
          |   }
          |""".stripMargin
    hydrateUnsafe(templateBody, parse(dehydrated)) shouldEqual parse(hydrated)
  }

  "Hydrate legacy template without package folder" in {
    val dehydrated =
      """{
          |  "id": "1",
          |  "status": "DEPRECATED_LEGACY_STATUS_ENUM_FINISHED"
          |}
          |""".stripMargin
    val hydrated =
      """{
          |  "resourceType": "Encounter",
          |  "id": "1",
          |  "status": "finished",
          |  "class": {
          |    "system": "https://bbl.health",
          |    "code": "some-value"
          |  },
          |  "identifier" : [
          |    {
          |      "value" : "1",
          |      "system" : "https://legacy.bbl.health/Encounter"
          |    }
          |  ]
          |}
          |""".stripMargin
    hydrateUnsafe(templateLegacy, parse(dehydrated)) shouldEqual parse(hydrated)
  }

  "Hydrate reference to nested resource with custom identifier" in {
    val dehydrated =
      """{
          |  "id": "1",
          |  "namespace": "testing",
          |  "observations": [{
          |    "id": "2"
          |  },
          |  {
          |    "id": "3"
          |  }]
          |}
          |""".stripMargin
    val hydrated =
      """[{
          |  "resourceType": "DiagnosticReport",
          |  "id": "1",
          |  "identifier": [{
          |    "system": "https://testing.nested.bbl.health/DiagnosticReport",
          |    "value": "1"
          |  }],
          |  "result": [{
          |    "reference": "https://testing.nested.bbl.health/Observation/2"
          |    },
          |    {
          |    "reference": "https://testing.nested.bbl.health/Observation/3"
          |  }]
          |},
          |{
          |  "resourceType": "Observation",
          |  "id": "2",
          |  "identifier": [{
          |    "system": "https://testing.nested.bbl.health/Observation",
          |    "value": "2"
          |  }]
          |},
          |{
          |  "resourceType": "Observation",
          |  "id": "3",
          |  "identifier": [{
          |    "system": "https://testing.nested.bbl.health/Observation",
          |    "value": "3"
          |  }]
          |}]
          |""".stripMargin


    given ReferenceProvider = IdentifierBasedReferenceProvider(
      (domain, resourceType) => s"https://${domain.toLowerCase}.bbl.health/$resourceType",
      """^https://([A-Za-z0-9_-]*\.)?([A-Za-z0-9_-]+)\.bbl\.health/([A-Za-z0-9_-]+)$""".r.matches
    )

    val h = Hydration(templates, redactObjects)

    h.hydrateUnsafe(TestHelpers.template("ResourceWithNamespace"), parse(dehydrated)) shouldEqual parse(hydrated)
  }

  "Hydrate array with optional and repeated parameter" in {
    val dehydrated =
      """{
          |  "subject": "ABC",
          |  "code": "testing",
          |  "altCode": [{
          |    "system": "test1",
          |    "code": "CODING_1"
          |  },
          |  {
          |    "system": "test2",
          |    "code": "CODING_2"
          |  }]
          |}
          |""".stripMargin
    val hydrated =
      """{
          |  "resourceType" : "Condition",
          |  "subject" : {
          |    "reference" : "https://patient.bbl.health/Patient/ABC"
          |  },
          |  "code" : { "coding": [
          |    {"system":"https://bbl.health", "code":"testing"},
          |    {"system":"test1", "code":"420420", "display":"lol"},
          |    {"system":"test2", "code":"696969", "display":"lol"}
          |  ]}
          |}
          |""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("OptionalCode")) shouldEqual parse(hydrated)
  }

  "Hydrate non-array with optional and repeated parameter" in {
    // This can happen if a field is made repeated and then we try to read old data
    val dehydrated =
      """{
          |  "subject": "ABC",
          |  "code": "testing",
          |  "altCode": {
          |    "system": "test1",
          |    "code": "CODING_1"
          |  }
          |}
          |""".stripMargin
    val hydrated =
      """{
          |  "resourceType" : "Condition",
          |  "subject" : {
          |    "reference" : "https://patient.bbl.health/Patient/ABC"
          |  },
          |  "code" : { "coding": [
          |    {"system":"https://bbl.health", "code":"testing"},
          |    {"system":"test1", "code":"420420", "display":"lol"}
          |  ]}
          |}
          |""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("OptionalCode")) shouldEqual parse(hydrated)
  }

  "Hydrate coding with babylon system when no other optional fields given" in {
    val dehydrated =
      """{
          |  "subject": "ABC",
          |  "altCode": [{
          |    "system": "test1",
          |    "code": "CODING_1"
          |  },
          |  {
          |    "system": "test2",
          |    "code": "CODING_2"
          |  }]
          |}
          |""".stripMargin
    val hydrated =
      """{
          |  "resourceType" : "Condition",
          |  "subject" : {
          |    "reference" : "https://patient.bbl.health/Patient/ABC"
          |  },
          |  "code" : { "coding": [
          |    {"system":"test1", "code":"420420", "display":"lol"},
          |    {"system":"test2", "code":"696969", "display":"lol"}
          |  ]}
          |}
          |""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("OptionalCode")) shouldEqual parse(hydrated)
  }

  "non-array identifier" in {
    val dehydrated =
      """
          |{
          |			"id": "bd3ff3ea-f6dc-3364-a65e-77d221d9baa7",
          |		  "subject": "6a72c8ac-20f7-48af-a7d3-b0610a424a07",
          |	  	"authored": "2020-11-17T14:30:51.56Z"
          |}
          |""".stripMargin
    val hydrated =
      """{
        |      "meta": {
        |        "profile": ["https://fhir.bbl.health/StructureDefinition/BblHealthAssessmentQuestionnaireResponse"]
        |      },
        |      "resourceType": "QuestionnaireResponse",
        |      "id": "bd3ff3ea-f6dc-3364-a65e-77d221d9baa7",
        |      "questionnaire": "https://blah.bbl.health/Questionnaire/1",
        |      "status": "completed",
        |      "subject": {
        |        "reference": "https://administration.bbl.health/Patient/6a72c8ac-20f7-48af-a7d3-b0610a424a07"
        |      },
        |      "authored": "2020-11-17T14:30:51.56Z",
        |      "identifier" : {
        |        "value" : "bd3ff3ea-f6dc-3364-a65e-77d221d9baa7",
        |        "system" : "https://healthassessment.bbl.health/QuestionnaireResponse"
        |      }
        |    }
          |""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("QuestionnaireResponseTest")) shouldEqual parse(hydrated)
  }

  "ABSENT enum in optional field" in {
    val dehydrated = """{"patientId": "ENUM_ALLOWED_ABSENT_ENUM_ABSENT"}"""
    val hydrated =
      """
        |{
        |      "id": "123-123-123",
        |      "resourceType": "Observation",
        |      "status": "final",
        |      "valueQuantity": {
        |        "value": 1,
        |        "system": "http://unitsofmeasure.org",
        |        "code": "[lb_av]"
        |      },
        |  "identifier" : [
        |    {
        |      "value" : "123-123-123",
        |      "system" : "https://testing.bbl.health/Observation"
        |    }
        |  ]
        |    }
        |""".stripMargin

    parseAndHydrate(dehydrated, TestHelpers.template("OptionalEnum")) shouldEqual parse(hydrated)
  }

  "ABSENT enum in required field" in {
    val dehydrated = """{"patientId": "ENUM_ALLOWED_ABSENT_ENUM_ABSENT"}"""
    val hydrated =
      """
        |{
        |      "id": "123-123-123",
        |      "resourceType": "Observation",
        |      "status": "final",
        |      "valueQuantity": {
        |        "value": 1,
        |        "unit": "a-thing",
        |        "system": "http://unitsofmeasure.org",
        |        "code": "[lb_av]"
        |      },
        |  "identifier" : [
        |    {
        |      "value" : "123-123-123",
        |      "system" : "https://testing.bbl.health/Observation"
        |    }
        |  ]
        |    }
        |""".stripMargin

    parseAndHydrate(dehydrated, TestHelpers.template("RequiredEnum")) shouldEqual parse(hydrated)
  }

  "int64 in template" in {
    val dehydrated = """{"idInt64": 1234, "nameInt64": 4321}"""
    val hydrated =
      """
        |{
        |  "id": "1234",
        |  "resourceType": "Patient",
        |  "name": [{
        |    "text" : "testing4321"
        |  }],
        |  "identifier" : [{
        |    "value" : "1234",
        |    "system" : "https://patient.bbl.health/Patient"
        |  }]
        |}
        |""".stripMargin

    parseAndHydrate(dehydrated, TestHelpers.template("PatientInt64")) shouldEqual parse(hydrated)
  }

  "can hydrate 'contained' parameters" - {
    "with IDs" in {
      val dehydrated = """{"name": "foo", "children": [{"id": "bar"}]}"""
      val hydrated =
        """
          |{
          |  "contained": [{
          |   "resourceType": "Organization",
          |   "id": "bar"
          |  }],
          |  "resourceType": "Organization",
          |  "name": "foo",
          |  "partOf": [{"reference": "#bar"}]
          |}
          |""".stripMargin

      parseAndHydrate(dehydrated, TestHelpers.template("Container")) shouldEqual parse(hydrated)
    }

    "without IDs" in {
      val dehydrated = """{"name": "foo", "children": [{"name": "bar"}, {"name": "baz"}]}"""
      val hydrated =
        """
          |{
          |  "contained": [
          |    {
          |      "resourceType": "Organization",
          |      "id": "children.0",
          |      "name": "bar"
          |    },
          |    {
          |      "resourceType": "Organization",
          |      "id": "children.1",
          |      "name": "baz"
          |    }
          |  ],
          |  "resourceType": "Organization",
          |  "name": "foo",
          |  "partOf": [{"reference": "#children.0"}, {"reference": "#children.1"}]
          |}
          |""".stripMargin

      parseAndHydrate(dehydrated, TestHelpers.template("Container")) shouldEqual parse(hydrated)
    }

    "when nested" in {
      val dehydrated = """{"name": "foo", "children": [{"name": "bar", "children": [{"name": "baz"}]}]}"""
      val hydrated =
        """
          |{
          |  "contained": [
          |    {
          |      "resourceType": "Organization",
          |      "id": "children.0",
          |      "name": "bar",
          |      "partOf": [{"reference": "#children.0.children.0"}]
          |    },
          |    {
          |      "resourceType": "Organization",
          |      "id": "children.0.children.0",
          |      "name": "baz"
          |    }
          |  ],
          |  "resourceType": "Organization",
          |  "name": "foo",
          |  "partOf": [{"reference": "#children.0"}]
          |}
          |""".stripMargin

      parseAndHydrate(dehydrated, TestHelpers.template("Container")) shouldEqual parse(hydrated)
    }
  }

  "Cannot inject params" - {
    "with other params" in {
      val dehydrated =
        """
          |{
          |		"patientId": "{{{timestamp}}}",
          |		"clinicianId": ["doctor-420"],
          |		"value": 69.0,
          |   "timestamp": "2020-05-21T17:21:32.12.123Z"
          |}
          |""".stripMargin
      val hydrated =
        """{
          |  "subject" : {
          |    "reference" : "https://administration.bbl.health/Patient/{{{timestamp}}}"
          |  },
          |  "valueQuantity" : {
          |    "value" : 69.0,
          |    "unit" : "lbs",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  },
          |  "resourceType" : "Observation",
          |  "code" : {
          |    "coding" : [
          |      {
          |        "system" : "https://bbl.health",
          |        "code" : "ykWNn2DwyB"
          |      }
          |    ]
          |  },
          |  "id" : "123-123-123",
          |  "status" : "final",
          |  "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |  "performer" : [
          |    {
          |      "reference" : "https://administration.bbl.health/Practitioner/doctor-420"
          |    }
          |  ],
          |  "identifier" : [
          |    {
          |      "value" : "123-123-123",
          |      "system" : "https://testing.bbl.health/Observation"
          |    }
          |  ]
          |}""".stripMargin
      parseAndHydrate(dehydrated) shouldEqual parse(hydrated)
    }

    "recursively" in {
      val dehydrated =
        """
          |{
          |		"patientId": "{{{patientId}}}",
          |		"clinicianId": ["doctor-420"],
          |		"value": 69.0,
          |   "timestamp": "2020-05-21T17:21:32.12.123Z"
          |}
          |""".stripMargin
      val hydrated =
        """{
          |  "subject" : {
          |    "reference" : "https://administration.bbl.health/Patient/{{{patientId}}}"
          |  },
          |  "valueQuantity" : {
          |    "value" : 69.0,
          |    "unit" : "lbs",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  },
          |  "resourceType" : "Observation",
          |  "code" : {
          |    "coding" : [
          |      {
          |        "system" : "https://bbl.health",
          |        "code" : "ykWNn2DwyB"
          |      }
          |    ]
          |  },
          |  "id" : "123-123-123",
          |  "status" : "final",
          |  "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |  "performer" : [
          |    {
          |      "reference" : "https://administration.bbl.health/Practitioner/doctor-420"
          |    }
          |  ],
          |  "identifier" : [
          |    {
          |      "value" : "123-123-123",
          |      "system" : "https://testing.bbl.health/Observation"
          |    }
          |  ]
          |}""".stripMargin
      parseAndHydrate(dehydrated) shouldEqual parse(hydrated)
    }
  }

  "Can hydrate models containing field 'reference' that isn't in a 'Reference'" in {
    // ClaimsRelated model has a field called "reference" (but it's unset in this dfhir)
    val dehydrated =
      """{
        |  "id" : "c3c1650a-035b-4cc5-b345-ca2e3455016a",
        |  "patient" : "c134f87b-db5a-4c88-a79c-eecdacd045e4",
        |  "created" : "2082-09-20T07:21:57Z",
        |  "related" : [{}]
        |}
        |""".stripMargin
    val hydrated =
      """{
        |  "resourceType": "Claim",
        |  "id": "c3c1650a-035b-4cc5-b345-ca2e3455016a",
        |  "status": "final",
        |  "use": "claim",
        |  "type": {
        |    "coding": [
        |      {
        |        "system": "http://terminology.hl7.org/CodeSystem/claim-type",
        |        "code": "vision"
        |      }
        |    ]
        |  },
        |  "patient": {
        |    "reference": "https://patient.bbl.health/Patient/c134f87b-db5a-4c88-a79c-eecdacd045e4"
        |  },
        |  "created": "2082-09-20T07:21:57Z",
        |  "provider": {"reference":  "https://external.bbl.health/Organization/someone"},
        |  "priority": {
        |    "coding": [
        |      {
        |        "display": "Normal",
        |        "code": "normal",
        |        "system": "http://hl7.org/fhir/ValueSet/process-priority"
        |      }
        |    ]
        |  },
        |  "insurance": [
        |    {
        |      "sequence": 1,
        |      "focal": true,
        |      "coverage": {"reference":  "https://external.bbl.health/Coverage/something"},
        |      "identifier": {
        |        "system": "https://claim.bbl.health/CodeSystem/claimNumber",
        |        "value": "1"
        |      }
        |    }
        |  ],
        |  "identifier" : [
        |    {
        |      "value" : "c3c1650a-035b-4cc5-b345-ca2e3455016a",
        |      "system" : "https://claim.bbl.health/Claim"
        |    }
        |  ]
        |}
        |""".stripMargin
    parseAndHydrate(dehydrated, TestHelpers.template("Claim")) shouldEqual parse(hydrated)
  }
}
