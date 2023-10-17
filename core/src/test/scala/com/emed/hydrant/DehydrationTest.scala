package com.emed.hydrant

import io.circe.Json
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Failure, Success, Try }
import TestHelpers.*
import com.emed.hydrant.HydrantError.ErrorOr

class DehydrationTest extends AnyFreeSpec with Matchers {

  given FhirTypeProvider = TestTypeProvider.typeProvider

  lazy val dehydration = new Dehydration(templates)

  val template                  = TestHelpers.template("BodyWeight")
  val templateEnum              = TestHelpers.template("CodingJson")
  val templateBody              = TestHelpers.template("PatientMetricsBodyMeasure")
  val templateMultipleResources = TestHelpers.template("CarePlan2")
  val optionalCode              = TestHelpers.template("OptionalCode")

  def dehydrate(template: Template)(fhir: Json) = dehydration.dehydrateUnsafe(template, fhir)
  def parseAndDehydrate(hydrated: String)       = dehydrate(template)(parse(hydrated))

  "Simple dehydrate Observation" in {
    val hydrated =
      """{
          |   "id": "123-123-123",
          |   "status":"final",
          |   "subject" : {
          |     "reference" : "https://administration.bbl.health/Patient/patient-123"
          |   },
          |   "valueQuantity" : {
          |     "value" : 69.0,
          |     "unit" : "lbs",
          |     "system" : "http://unitsofmeasure.org",
          |     "code" : "[lb_av]"
          |   },
          |   "resourceType" : "Observation",
          |   "code" : {
          |     "coding" : [
          |       {
          |         "system" : "https://bbl.health",
          |         "code" : "ykWNn2DwyB"
          |       }
          |     ]
          |   },
          |   "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |   "performer" : [
          |     {
          |       "reference" : "https://administration.bbl.health/Practitioner/doctor-420"
          |     }
          |   ]
          | }
          |""".stripMargin
    val dehydrated =
      """
          |{
          |		"patientId": "patient-123",
          |		"clinicianId": ["doctor-420"],
          |		"value": 69.0,
          |   "timestamp": "2020-05-21T17:21:32.12.123Z"
          |}
          |""".stripMargin
    parseAndDehydrate(hydrated) shouldEqual parse(dehydrated)
  }
  "With order disambiguation strategy" in {
    val fhir =
      """{
       |      "resourceType" : "Observation",
       |      "id" : "189596e1-9fd2-34a0-b7fa-b701c2de25dc",
       |      "meta" : {
       |        "profile" : [
       |          "https://fhir.bbl.health/StructureDefinition/BblVitalSignsBloodPressure"
       |        ]
       |      },
       |      "code" : {
       |        "coding" : [
       |          {
       |            "code" : "DcuIExT7O8",
       |            "system" : "https://bbl.health",
       |            "display" : "Blood pressure"
       |          }
       |        ]
       |      },
       |      "status" : "final",
       |      "subject" : {
       |        "reference" : "https://patient.bbl.health/Patient/93ba50ef-7d31-4272-af91-2473cca9b426"
       |      },
       |      "encounter" : {
       |        "reference" : "https://triage.bbl.health/Encounter/b77a2df9-c324-4366-89ff-21947f42294b"
       |      },
       |      "performer" : [
       |        {
       |          "reference" : "https://patient.bbl.health/Patient/8f82c2f3-ef97-4732-a569-622a5fbaacfc"
       |        }
       |      ],
       |      "effectiveDateTime" : "2020-11-30T10:31:06.405Z",
       |      "component" : [
       |        {
       |          "code" : {
       |            "coding" : [
       |              {
       |                "code" : "yP1EGhN9d8",
       |                "system" : "https://bbl.health",
       |                "display" : "Systolic arterial pressure"
       |              }
       |            ]
       |          },
       |          "valueQuantity" : {
       |            "unit" : "mmHg",
       |            "code" : "mm[Hg]",
       |            "value" : 120,
       |            "system" : "http://unitsofmeasure.org"
       |          }
       |        },
       |        {
       |          "code" : {
       |            "coding" : [
       |              {
       |                "code" : "kETZtQ0TFK",
       |                "system" : "https://bbl.health",
       |                "display" : "Diastolic arterial pressure"
       |              }
       |            ]
       |          },
       |          "valueQuantity" : {
       |            "unit" : "mmHg",
       |            "code" : "mm[Hg]",
       |            "value" : 100,
       |            "system" : "http://unitsofmeasure.org"
       |          }
       |        }
       |      ]
       |    }""".stripMargin

    val dehydrated =
      """{
          |    "id": "189596e1-9fd2-34a0-b7fa-b701c2de25dc",
          |    "patient": "93ba50ef-7d31-4272-af91-2473cca9b426",
          |    "effectiveDateTime": "2020-11-30T10:31:06.405Z",
          |    "encounter": {
          |        "encounterId": "b77a2df9-c324-4366-89ff-21947f42294b",
          |        "encounterType": "COMMON_ENCOUNTER_TYPE_TRIAGE"
          |    },
          |    "status": "COMMON_OBSERVATION_STATUS_FINAL",
          |    "diastolicBpValue": 100,
          |    "systolicBpValue": 120,
          |    "performer": {
          |        "performerType": "COMMON_PERFORMER_TYPE_PATIENT",
          |        "performer": "8f82c2f3-ef97-4732-a569-622a5fbaacfc"
          |    }
          |}""".stripMargin

    val t           = TestHelpers.template("VitalSignsBloodPressure2")
    val dehydration = Dehydration(templates, Order)
    dehydration.dehydrateUnsafe(t, parse(fhir)) shouldEqual parse(dehydrated)
  }

  "Missing optional fields" in {
    val hydrated =
      """{
          |   "valueQuantity" : {
          |     "unit" : "lbs",
          |     "system" : "http://unitsofmeasure.org",
          |     "code" : "[lb_av]"
          |   },
          |   "id": "123-123-123",
          |   "status":"final",
          |   "resourceType" : "Observation",
          |   "code" : {
          |     "coding" : [
          |       {
          |         "system" : "https://bbl.health",
          |         "code" : "ykWNn2DwyB"
          |       }
          |     ]
          |   },
          |   "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z"
          | }
          |""".stripMargin
    val dehydrated =
      """
          |{
          |   "timestamp": "2020-05-21T17:21:32.12.123Z"
          |}
          |""".stripMargin
    parseAndDehydrate(hydrated) shouldEqual parse(dehydrated)
  }

  "Given optional babylon code" in {
    val hydrated =
      """{
          |  "resourceType" : "Condition",
          |  "subject" : {
          |    "reference" : "https://patient.bbl.health/Patient/ABC"
          |  },
          |  "code" : { "coding": [
          |    {"system":"https://bbl.health", "code":"420420"}
          |  ]}
          |}
          |""".stripMargin
    val dehydrated =
      """{
          |  "subject": "ABC",
          |  "code": "420420"
          |}
          |""".stripMargin
    dehydrate(optionalCode)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Given optional babylon code and array" in {
    val hydrated =
      """{
          |  "resourceType" : "Condition",
          |  "subject" : {
          |    "reference" : "https://patient.bbl.health/Patient/ABC"
          |  },
          |  "code" : { "coding": [
          |    {"system":"https://bbl.health", "code":"420420"},
          |    {"system":"test2", "code":"696969", "display":"lol"}
          |  ]}
          |}
          |""".stripMargin
    val dehydrated =
      """{
          |  "subject": "ABC",
          |  "code": "420420",
          |  "altCode": [{
          |    "system": "test2",
          |    "code": "CODING_2"
          |  }]
          |}
          |""".stripMargin
    dehydrate(optionalCode)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Missing optional babylon code" in {
    val hydrated =
      """{
          |  "resourceType" : "Condition",
          |  "subject" : {
          |    "reference" : "https://patient.bbl.health/Patient/ABC"
          |  }
          |}
          |""".stripMargin
    val dehydrated =
      """{
          |  "subject": "ABC"
          |}
          |""".stripMargin
    dehydrate(optionalCode)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Missing optional babylon code in array" in {
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
    dehydrate(optionalCode)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "List of References (Practitioners)" in {

    val hydrated =
      """{
          |   "subject" : {
          |     "reference" : "https://administration.bbl.health/Patient/patient-123"
          |   },
          |   "id": "123-123-123",
          |   "status":"final",
          |   "valueQuantity" : {
          |     "value" : 69.0,
          |     "unit" : "lbs",
          |     "system" : "http://unitsofmeasure.org",
          |     "code" : "[lb_av]"
          |   },
          |   "resourceType" : "Observation",
          |   "code" : {
          |     "coding" : [
          |       {
          |         "system" : "https://bbl.health",
          |         "code" : "ykWNn2DwyB"
          |       }
          |     ]
          |   },
          |   "effectiveDateTime" : "2020-05-21T17:21:32.12.123Z",
          |   "performer" : [
          |     {
          |       "reference" : "https://administration.bbl.health/Practitioner/doctor-420"
          |     },
          |     {
          |       "reference" : "https://administration.bbl.health/Practitioner/doctor-69"
          |     },
          |     {
          |       "reference" : "https://administration.bbl.health/Practitioner/doctor-1337"
          |     }
          |   ]
          | }""".stripMargin
    val dehydrated =
      """
          |{
          |		"patientId": "patient-123",
          |		"clinicianId": ["doctor-420", "doctor-69", "doctor-1337"],
          |		"value": 69.0,
          |   "timestamp": "2020-05-21T17:21:32.12.123Z"
          |}
          |""".stripMargin
    parseAndDehydrate(hydrated) shouldEqual parse(dehydrated)
  }

  "Dehydrate with json enum values" in {
    val dehydrated =
      """
          |{
          |   "status": "OBSERVATION_STATUS_ENUM_AMENDED",
          |   "code": "CODING_2",
          |   "valueQuantity": "OBSERVATION_VALUE_QUANTITY_HIGH",
          |   "bodySite": "OBSERVATION_BODY_SITE_HEAD_AND_NECK"
          |}
          |""".stripMargin

    val hydrated =
      """{
          |   "id" : "1",
          |   "resourceType": "Observation",
          |   "status": "amended",
          |   "code": {
          |     "system": "https://bbl.health",
          |     "code": "696969",
          |     "display": "cheeky"
          |   },
          |   "valueQuantity": {
          |     "value": 10,
          |     "code": "kg",
          |     "system": "http://unitsofmeasure.org"
          |   },
          |   "bodySite": {
          |     "coding": [{
          |       "system": "http://snomed.info/sct",
          |       "code": "774007"
          |     }]
          |   }
          | }
          |""".stripMargin
    dehydrate(templateEnum)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Dehydrate on inherit templates" in {
    val dehydrated =
      """{          
          |   "value": 2,
          |   "type": "PATIENT_METRICS_BODY_MEASURE_CHILD_TEMPLATE_HEIGHT_IN_M"
          |}
          |""".stripMargin
    val hydrated =
      """{
          |     "id": "1",
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
          |    ],
          |     "valueQuantity" : {
          |       "value" : 2,
          |       "unit" : "[m]",
          |       "system" : "http://unitsofmeasure.org",
          |       "code" : "m"
          |     },
          |     "url" : "https://fhir.bbl.health/Observation/1",
          |     "referenceRange" : [
          |       {
          |         "low" : {
          |           "value" : 1.50,
          |           "code" : "m",
          |           "unit" : "[m]",
          |           "system" : "http://unitsofmeasure.org"
          |         },
          |         "high" : {
          |           "value" : 1.94,
          |           "code" : "m",
          |           "unit" : "[m]",
          |           "system" : "http://unitsofmeasure.org"
          |         },
          |         "text" : "normal"
          |       },
          |       {
          |         "low" : {
          |           "value" : 0.00,
          |           "code" : "m",
          |           "unit" : "[m]",
          |           "system" : "http://unitsofmeasure.org"
          |         },
          |         "high" : {
          |           "value" : 0.01,
          |           "code" : "m",
          |           "unit" : "[m]",
          |           "system" : "http://unitsofmeasure.org"
          |         },
          |         "text" : "possibly an ant?"
          |       }
          |     ]
          |   }
          |""".stripMargin
    dehydrate(templateBody)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Dehydrate with provided optional param" in {
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
    val dehydrated =
      """
          |{
          |   "timestamp": "2020-05-21T17:21:32.12.123Z",
          |   "propagate": [{
          |     "code": "abcd"
          |   }]
          |}
          |""".stripMargin
    dehydrate(TestHelpers.template("PropagateOptional"))(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Dehydrate with not provided optional param" in {
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
    val dehydrated =
      """
          |{
          |   "propagate": [{
          |     "code": "abcd"
          |   }]
          |}
          |""".stripMargin
    dehydrate(TestHelpers.template("PropagateOptional"))(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Can dehydrate array of resources" in {
    val dehydrated =
      """{
         |   "id": "CARE_PLAN",
         |   "patient": "PATIENT",
         |   "created": "2020-05-21T17:21:32.12.123Z",
         |   "status": "CARE_PLAN_STATUS_ACTIVE",
         |   "contributors": ["CONTRIBUTORS"],
         |   "encounter": "ENCOUNTER",
         |   "goals": [
         |     {"id": "GOAL", "start": "2020-05-21T17:21:32.12.123Z", "note": "blah goal"}
         |   ]
         |}""".stripMargin
    val hydrated =
      """[
          |  {
          |    "resourceType": "CarePlan",
          |    "meta": {"profile": ["https://fhir.bbl.health/StructureDefinition/BblCarePlan"]},
          |    "id": "CARE_PLAN",
          |    "identifier": [{"value": "CARE_PLAN", "system": "https://careplan.bbl.health/CarePlan"}],
          |    "contributor": [{"reference": "https://administration.bbl.health/Practitioner/CONTRIBUTORS"}],
          |    "subject": {"reference": "https://administration.bbl.health/Patient/PATIENT"},
          |    "intent": "plan",
          |    "goal": [{"reference": "https://careplan.bbl.health/Goal/GOAL"}],
          |    "status": "active",
          |    "encounter": {"reference": "https://consultation.bbl.health/Encounter/ENCOUNTER"},
          |    "created": "2020-05-21T17:21:32.12.123Z"
          |  },
          |  {
          |    "resourceType": "Goal",
          |    "meta": {"profile": ["https://fhir.bbl.health/StructureDefinition/BblHealthGoal"]},
          |    "id": "GOAL",
          |    "identifier" : [{"system": "https://careplan.bbl.health/Goal", "value": "GOAL"}],
          |    "subject": {"reference": "Patient/PATIENT"},
          |    "description": {"coding": [{"system": "https://bbl.health", "code": "hma5EurU4w"}]},
          |    "note": [{"text": "blah goal"}],
          |    "startDate" : "2020-05-21T17:21:32.12.123Z",
          |    "lifecycleStatus" : "planned"
          |  }
          |]""".stripMargin
    dehydrate(templateMultipleResources)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Can dehydrate contained resource reference" in {
    val dehydrated =
      """{
          |   "id": "CARE_PLAN",
          |   "patient": "PATIENT",
          |   "created": "2020-05-21T17:21:32.12.123Z",
          |   "status": "CARE_PLAN_STATUS_ACTIVE",
          |   "contributors": ["CONTRIBUTORS"],
          |   "encounter": "ENCOUNTER",
          |   "goals": [
          |     {"id": "GOAL", "start": "2020-05-21T17:21:32.12.123Z", "note": "blah goal"}
          |   ]
          |}""".stripMargin
    val hydrated =
      """{
          |  "resourceType": "CarePlan",
          |  "meta": {"profile": ["https://fhir.bbl.health/StructureDefinition/BblCarePlan"]},
          |  "id": "CARE_PLAN",
          |  "identifier": [{"value": "CARE_PLAN", "system": "https://careplan.bbl.health/CarePlan"}],
          |  "contributor": [{"reference": "https://administration.bbl.health/Practitioner/CONTRIBUTORS"}],
          |  "subject": {"reference": "https://administration.bbl.health/Patient/PATIENT"},
          |  "intent": "plan",
          |  "goal": [{"reference": "#GOAL"}],
          |  "status": "active",
          |  "encounter": {"reference": "https://consultation.bbl.health/Encounter/ENCOUNTER"},
          |  "created": "2020-05-21T17:21:32.12.123Z",
          |  "contained": [{
          |    "resourceType": "Goal",
          |    "meta": {"profile": ["https://fhir.bbl.health/StructureDefinition/BblHealthGoal"]},
          |    "id": "GOAL",
          |    "identifier" : [{"system": "https://careplan.bbl.health/Goal", "value": "GOAL"}],
          |    "subject": {"reference": "Patient/PATIENT"},
          |    "description": {"coding": [{"system": "https://bbl.health", "code": "hma5EurU4w"}]},
          |    "note": [{"text": "blah goal"}],
          |    "startDate" : "2020-05-21T17:21:32.12.123Z",
          |    "lifecycleStatus" : "planned"
          |  }]
          |}""".stripMargin
    dehydrate(templateMultipleResources)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Can dehydrate relative resource references" in {
    val dehydrated =
      """{
          |   "id": "CARE_PLAN",
          |   "patient": "PATIENT",
          |   "created": "2020-05-21T17:21:32.12.123Z",
          |   "status": "CARE_PLAN_STATUS_ACTIVE",
          |   "contributors": ["CONTRIBUTORS"],
          |   "encounter": "ENCOUNTER",
          |   "goals": [
          |     {"id": "GOAL", "start": "2020-05-21T17:21:32.12.123Z", "note": "blah goal"}
          |   ]
          |}""".stripMargin
    val hydrated =
      """[
          |  {
          |    "resourceType": "CarePlan",
          |    "meta": {"profile": ["https://fhir.bbl.health/StructureDefinition/BblCarePlan"]},
          |    "id": "CARE_PLAN",
          |    "identifier": [{"value": "CARE_PLAN", "system": "https://careplan.bbl.health/CarePlan"}],
          |    "contributor": [{"reference": "https://administration.bbl.health/Practitioner/CONTRIBUTORS"}],
          |    "subject": {"reference": "https://administration.bbl.health/Patient/PATIENT"},
          |    "intent": "plan",
          |    "goal": [{"reference": "Goal/GOAL"}],
          |    "status": "active",
          |    "encounter": {"reference": "https://consultation.bbl.health/Encounter/ENCOUNTER"},
          |    "created": "2020-05-21T17:21:32.12.123Z"
          |  },
          |  {
          |    "resourceType": "Goal",
          |    "id": "GOAL",
          |    "meta": {"profile": ["https://fhir.bbl.health/StructureDefinition/BblHealthGoal"]},
          |    "identifier" : [{"system": "https://careplan.bbl.health/Goal", "value": "GOAL"}],
          |    "subject": {"reference": "Patient/PATIENT"},
          |    "description": {"coding": [{"system": "https://bbl.health", "code": "hma5EurU4w"}]},
          |    "note": [{"text": "blah goal"}],
          |    "startDate" : "2020-05-21T17:21:32.12.123Z",
          |    "lifecycleStatus" : "planned"
          |  }
          |]""".stripMargin
    dehydrate(templateMultipleResources)(parse(hydrated)) shouldEqual parse(dehydrated)
  }

  "Enum value dehydrates to ABSENT when" - {
    lazy val hydratedMissingValue = parse("""
          |{
          |  "id" : "123-123-123",
          |  "resourceType" : "Observation",
          |  "status" : "final",
          |  "valueQuantity" : {
          |    "value" : 1,
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  }
          |}
          |""".stripMargin)

    lazy val hydratedNullValue = parse("""
          |{
          |  "id" : "123-123-123",
          |  "resourceType" : "Observation",
          |  "status" : "final",
          |  "valueQuantity" : {
          |    "value" : 1,
          |    "unit": null,
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  }
          |}
          |""".stripMargin)

    lazy val hydratedDefaultValue = parse("""
          |{
          |  "id" : "123-123-123",
          |  "resourceType" : "Observation",
          |  "status" : "final",
          |  "valueQuantity" : {
          |    "value" : 1,
          |    "unit": "a-thing",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  }
          |}
          |""".stripMargin)

    lazy val optionalEnum = TestHelpers.template("OptionalEnum")
    lazy val requiredEnum = TestHelpers.template("RequiredEnum")

    "missing and optional" in {
      dehydrate(optionalEnum)(hydratedMissingValue) shouldEqual Json.obj()
    }

    "null and optional" in {
      dehydrate(optionalEnum)(hydratedNullValue) shouldEqual Json.obj()
    }

    "default and optional" in {
      dehydrate(optionalEnum)(hydratedDefaultValue) shouldEqual Json.obj()
    }

    "missing and required" in {
      dehydrate(requiredEnum)(hydratedMissingValue) shouldEqual Json.obj()
    }

    "null and required" in {
      dehydrate(requiredEnum)(hydratedNullValue) shouldEqual Json.obj()
    }

    "default and required" in {
      dehydrate(requiredEnum)(hydratedDefaultValue) shouldEqual Json.obj()
    }
  }

  "When the default enum value clashes with a real enum value, dehydrate to the real enum value" in {
    val template = TestHelpers.template("ObservationEnumWithClashingDefault")
    dehydrate(template)(parse("""{
          |  "id" : "123-123-123",
          |  "resourceType" : "Observation",
          |  "status" : "final",
          |  "valueQuantity" : {
          |    "value" : 1,
          |    "unit": "baz",
          |    "system" : "http://unitsofmeasure.org",
          |    "code" : "[lb_av]"
          |  }
          |}""".stripMargin)) shouldEqual parse("""{"unit": "ENUM_WITH_CLASHING_DEFAULT_BAZ"}""")
  }

  "Error if not all array values are captured" in {
    val template = TestHelpers.template("NestedConcept")

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
          |        "system" : "https://bbl.wat2",
          |        "code" : "696969",
          |        "display" : "this does not match the template"
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

    Try(dehydrate(template)(parse(hydrated))) match {
      case Success(dfhir) => fail(s"Expected to throw, instead successfully dehydrated: $dfhir")
      case _              =>
    }
  }

  "Error on missing required fields should error" in {
    val template = TestHelpers.template("NestedConcept")

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

    Try(dehydrate(template)(parse(hydrated))) match {
      case Success(dfhir) => fail(s"Expected to throw, instead successfully dehydrated: $dfhir")
      case _              =>
    }
  }

  "List templates" - {
    "Dehydrate list template" in {
      val template = TestHelpers.template("LabsServiceRequestList")
      val hydrated =
        """[
            |{
            |  "occurrenceDateTime" : "2070-03-31T10:19:05Z",
            |  "subject" : {
            |    "reference" : "https://patient.bbl.health/Patient/18d464c1-e78a-44d4-8538-93b185d5ea13"
            |  },
            |  "resourceType" : "ServiceRequest",
            |  "requester" : {
            |    "reference" : "https://clinician.bbl.health/Practitioner/b84f004f-81f3-4556-ba67-1a4f62593874"
            |  },
            |  "code" : {
            |    "text" : "Gst26wg",
            |    "coding" : [
            |      {
            |        "code" : "-irgaJLd_c",
            |        "system" : "https://bbl.health"
            |      }
            |    ]
            |  },
            |  "note" : [
            |    {
            |      "text" : "urz9wm"
            |    },
            |    {
            |      "text" : "0"
            |    }
            |  ],
            |  "status" : "revoked",
            |  "category" : [
            |    {
            |      "coding" : [
            |        {
            |          "display" : "Laboratory procedure (procedure)",
            |          "code" : "XjM40hb6i8",
            |          "system" : "https://bbl.health"
            |        }
            |      ]
            |    }
            |  ],
            |  "encounter" : {
            |    "reference" : "https://consultation.bbl.health/Encounter/385efe0a-27b6-45de-89a2-66e785413f15"
            |  },
            |  "extension" : [
            |    {
            |      "url" : "https://fhir.bbl.health/StructureDefinition/BblAthenaFacilityId",
            |      "valueString" : "qnhiyWzfn"
            |    }
            |  ],
            |  "supportingInfo" : [
            |    {
            |      "reference" : "https://labs.bbl.health/DocumentReference/d7993693-7bc9-4b26-a199-cb7b88ac3846"
            |    }
            |  ],
            |  "priority" : "urgent",
            |  "intent" : "order",
            |  "authoredOn" : "1974-06-09T14:51:36Z",
            |  "id" : "e6e308f4-9db0-4037-b2cc-1e8b18743051",
            |  "meta" : {
            |    "profile" : [
            |      "https://fhir.bbl.health/StructureDefinition/BblLabsServiceRequest"
            |    ]
            |  },
            |  "identifier" : [
            |    {
            |      "value" : "e6e308f4-9db0-4037-b2cc-1e8b18743051",
            |      "system" : "https://labs.bbl.health/ServiceRequest"
            |    }
            |  ]
            |},
            | {
            |  "resourceType" : "DocumentReference",
            |  "id" : "d7993693-7bc9-4b26-a199-cb7b88ac3846",
            |  "meta" : {
            |    "profile" : [
            |      "https://fhir.bbl.health/StructureDefinition/BblLabsServiceRequestSupportingInfo"
            |    ]
            |  },
            |  "status" : "current",
            |  "content" : [
            |    {
            |      "attachment" : {
            |        "url" : "a6c0jczgpq1"
            |      }
            |    }
            |  ],
            |  "identifier" : [
            |    {
            |      "value" : "d7993693-7bc9-4b26-a199-cb7b88ac3846",
            |      "system" : "https://labs.bbl.health/DocumentReference"
            |    }
            |  ]
            |}
            |]
            |""".stripMargin

      val expected =
        """
            |{
            |  "serviceRequest" : [
            |    {
            |      "id" : "e6e308f4-9db0-4037-b2cc-1e8b18743051",
            |      "status" : "LABS_SERVICE_REQUEST_STATUS_REVOKED",
            |      "subject" : "18d464c1-e78a-44d4-8538-93b185d5ea13",
            |      "priority" : "LABS_SERVICE_REQUEST_PRIORITY_URGENT",
            |      "code" : "-irgaJLd_c",
            |      "encounter" : "385efe0a-27b6-45de-89a2-66e785413f15",
            |      "authoredOn" : "1974-06-09T14:51:36Z",
            |      "requester" : "b84f004f-81f3-4556-ba67-1a4f62593874",
            |      "note" : [
            |        {
            |          "text" : "urz9wm"
            |        },
            |        {
            |          "text" : "0"
            |        }
            |      ],
            |      "supportingInfo" : [
            |        {
            |          "supportingInfoId" : "d7993693-7bc9-4b26-a199-cb7b88ac3846",
            |          "supportingInfoUrl" : "a6c0jczgpq1"
            |        }
            |      ],
            |      "category" : [
            |        "LABS_SERVICE_REQUEST_CATEGORY_LABORATORY_PROCEDURE"
            |      ],
            |      "occurrenceDateTime" : "2070-03-31T10:19:05Z",
            |      "codeText" : "Gst26wg",
            |      "facilityId" : "qnhiyWzfn"
            |    }
            |  ]
            |}
            |""".stripMargin

      dehydrate(template)(parse(hydrated)) shouldEqual parse(expected)
    }

    "Error if extra fhir resources in list" in {
      val template = TestHelpers.template("LabsServiceRequestList")
      val hydrated =
        """[
            |{
            |  "occurrenceDateTime" : "2070-03-31T10:19:05Z",
            |  "subject" : {
            |    "reference" : "https://patient.bbl.health/Patient/18d464c1-e78a-44d4-8538-93b185d5ea13"
            |  },
            |  "resourceType" : "ServiceRequest",
            |  "requester" : {
            |    "reference" : "https://clinician.bbl.health/Practitioner/b84f004f-81f3-4556-ba67-1a4f62593874"
            |  },
            |  "code" : {
            |    "text" : "Gst26wg",
            |    "coding" : [
            |      {
            |        "code" : "-irgaJLd_c",
            |        "system" : "https://bbl.health"
            |      }
            |    ]
            |  },
            |  "note" : [
            |    {
            |      "text" : "urz9wm"
            |    },
            |    {
            |      "text" : "0"
            |    }
            |  ],
            |  "status" : "revoked",
            |  "category" : [
            |    {
            |      "coding" : [
            |        {
            |          "display" : "Laboratory procedure (procedure)",
            |          "code" : "XjM40hb6i8",
            |          "system" : "https://bbl.health"
            |        }
            |      ]
            |    }
            |  ],
            |  "encounter" : {
            |    "reference" : "https://consultation.bbl.health/Encounter/385efe0a-27b6-45de-89a2-66e785413f15"
            |  },
            |  "extension" : [
            |    {
            |      "url" : "https://fhir.bbl.health/StructureDefinition/BblAthenaFacilityId",
            |      "valueString" : "qnhiyWzfn"
            |    }
            |  ],
            |  "supportingInfo" : [
            |    {
            |      "reference" : "https://labs.bbl.health/DocumentReference/d7993693-7bc9-4b26-a199-cb7b88ac3846"
            |    }
            |  ],
            |  "priority" : "urgent",
            |  "intent" : "order",
            |  "authoredOn" : "1974-06-09T14:51:36Z",
            |  "id" : "e6e308f4-9db0-4037-b2cc-1e8b18743051",
            |  "meta" : {
            |    "profile" : [
            |      "https://fhir.bbl.health/StructureDefinition/BblLabsServiceRequest"
            |    ]
            |  },
            |  "identifier" : [
            |    {
            |      "value" : "e6e308f4-9db0-4037-b2cc-1e8b18743051",
            |      "system" : "https://labs.bbl.health/ServiceRequest"
            |    }
            |  ]
            |},
            | {
            |  "resourceType" : "DocumentReference",
            |  "id" : "d7993693-7bc9-4b26-a199-cb7b88ac3846",
            |  "meta" : {
            |    "profile" : [
            |      "https://fhir.bbl.health/StructureDefinition/BblLabsServiceRequestSupportingInfo"
            |    ]
            |  },
            |  "status" : "current",
            |  "content" : [
            |    {
            |      "attachment" : {
            |        "url" : "a6c0jczgpq1"
            |      }
            |    }
            |  ],
            |  "identifier" : [
            |    {
            |      "value" : "d7993693-7bc9-4b26-a199-cb7b88ac3846",
            |      "system" : "https://labs.bbl.health/DocumentReference"
            |    }
            |  ]
            |},
            | {
            |  "resourceType" : "DocumentReference",
            |  "id" : "d7993693-7bc9-4b26-a199-cb7b88ac3847",
            |  "meta" : {
            |    "profile" : [
            |      "https://fhir.bbl.health/StructureDefinition/BblLabsServiceRequestSupportingInfo"
            |    ]
            |  },
            |  "status" : "current",
            |  "content" : [
            |    {
            |      "attachment" : {
            |        "url" : "a6c0jczgpq1"
            |      }
            |    }
            |  ],
            |  "identifier" : [
            |    {
            |      "value" : "d7993693-7bc9-4b26-a199-cb7b88ac3846",
            |      "system" : "https://labs.bbl.health/DocumentReference"
            |    }
            |  ]
            |}
            |]
            |""".stripMargin

      Try(dehydrate(template)(parse(hydrated))) match {
        case Success(dfhir) => fail(s"Expected to throw, instead successfully dehydrated: $dfhir")
        case Failure(exception) =>
          exception.getMessage shouldEqual
          "Unable to dehydrate all fhir resources into template LabsServiceRequestList: [(id: \"d7993693-7bc9-4b26-a199-cb7b88ac3846\", resourceType: \"DocumentReference\")]"
      }
    }

    "Error if one of the fhir resources fails to dehydrate" in {
      val template = TestHelpers.template("LabsServiceRequestList")
      val hydrated =
        """[
            |{
            |  "occurrenceDateTime" : "2070-03-31T10:19:05Z",
            |  "subject" : {
            |    "reference" : "https://patient.bbl.health/Patient/18d464c1-e78a-44d4-8538-93b185d5ea13"
            |  },
            |  "resourceType" : "ServiceRequest",
            |  "requester" : {
            |    "reference" : "https://clinician.bbl.health/Practitioner/b84f004f-81f3-4556-ba67-1a4f62593874"
            |  },
            |  "code" : {
            |    "text" : "Gst26wg",
            |    "coding" : [
            |      {
            |        "code" : "-irgaJLd_c",
            |        "system" : "https://bbl.health"
            |      }
            |    ]
            |  },
            |  "note" : [
            |    {
            |      "text" : "urz9wm"
            |    },
            |    {
            |      "text" : "0"
            |    }
            |  ],
            |  "status" : "revoked",
            |  "category" : [
            |    {
            |      "coding" : [
            |        {
            |          "display" : "Laboratory procedure (procedure)",
            |          "code" : "XjM40hb6i8",
            |          "system" : "https://bbl.health"
            |        }
            |      ]
            |    }
            |  ],
            |  "extension" : [
            |    {
            |      "url" : "https://fhir.bbl.health/StructureDefinition/BblAthenaFacilityId",
            |      "valueString" : "qnhiyWzfn"
            |    }
            |  ],
            |  "supportingInfo" : [
            |    {
            |      "reference" : "https://labs.bbl.health/DocumentReference/d7993693-7bc9-4b26-a199-cb7b88ac3846"
            |    }
            |  ],
            |  "priority" : "urgent",
            |  "intent" : "order",
            |  "authoredOn" : "1974-06-09T14:51:36Z",
            |  "id" : "e6e308f4-9db0-4037-b2cc-1e8b18743051",
            |  "meta" : {
            |    "profile" : [
            |      "https://fhir.bbl.health/StructureDefinition/BblLabsServiceRequest"
            |    ]
            |  },
            |  "identifier" : [
            |    {
            |      "value" : "e6e308f4-9db0-4037-b2cc-1e8b18743051",
            |      "system" : "https://labs.bbl.health/ServiceRequest"
            |    }
            |  ]
            |},
            | {
            |  "resourceType" : "DocumentReference",
            |  "id" : "d7993693-7bc9-4b26-a199-cb7b88ac3846",
            |  "meta" : {
            |    "profile" : [
            |      "https://fhir.bbl.health/StructureDefinition/BblLabsServiceRequestSupportingInfo"
            |    ]
            |  },
            |  "status" : "current",
            |  "content" : [
            |    {
            |      "attachment" : {
            |        "url" : "a6c0jczgpq1"
            |      }
            |    }
            |  ],
            |  "identifier" : [
            |    {
            |      "value" : "d7993693-7bc9-4b26-a199-cb7b88ac3846",
            |      "system" : "https://labs.bbl.health/DocumentReference"
            |    }
            |  ]
            |}
            |]
            |""".stripMargin

      Try(dehydrate(template)(parse(hydrated))) match {
        case Success(dfhir) => fail(s"Expected to throw, instead successfully dehydrated: $dfhir")
        case Failure(exception) =>
          exception.getMessage shouldEqual
          "Unable to dehydrate all fhir resources into template LabsServiceRequestList: [(id: \"e6e308f4-9db0-4037-b2cc-1e8b18743051\", resourceType: \"ServiceRequest\"), (id: \"d7993693-7bc9-4b26-a199-cb7b88ac3846\", resourceType: \"DocumentReference\")]"
      }
    }
  }

  "Dehydrate list with 1 specific and 1 general" in {
    val template = TestHelpers.template("PartnerOrganization")
    val hydrated =
      """{
          |      "name" : "",
          |      "identifier" : [
          |        {
          |          "system" : "https://partner.bbl.health/Organization",
          |          "value" : "PyIizbrxu"
          |        },
          |        {
          |          "system" : "some_other_system",
          |          "value" : "blah"
          |        }
          |      ],
          |      "resourceType" : "Organization",
          |      "alias" : [
          |        ""
          |      ],
          |      "id" : "PyIizbrxu",
          |      "meta" : {
          |        "profile" : [
          |          "https://fhir.bbl.health/StructureDefinition/BblPartnerOrganization"
          |        ]
          |      },
          |      "active" : false,
          |      "extension" : [
          |        {
          |          "url" : "https://fhir.bbl.health/StructureDefinition/BblPartnerOrganizationTypeLabel",
          |          "valueString" : ""
          |        }
          |      ]
          |    }""".stripMargin

    val expected =
      """{
          |      "id" : "PyIizbrxu",
          |      "identifier" : [
          |        {
          |          "system" : "some_other_system",
          |          "value" : "blah"
          |        }
          |      ],
          |      "active" : false,
          |      "name" : "",
          |      "alias" : "",
          |      "typeLabel" : ""
          |    }
          |""".stripMargin

    dehydrate(template)(parse(hydrated)) shouldEqual parse(expected)
  }
}
