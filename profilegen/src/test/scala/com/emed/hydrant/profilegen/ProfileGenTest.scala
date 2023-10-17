package com.emed.hydrant.profilegen

import com.emed.hydrant.{ReferenceProvider, TestHelpers}
import com.emed.hydrant.profilegen.ConformanceResourceFile
import com.babylonhealth.lit.core.*
import com.babylonhealth.lit.core.ChoiceImplicits.*
import com.babylonhealth.lit.core.model.{CodeableConcept, Coding, Meta}
import com.babylonhealth.lit.core.serdes.*
import com.babylonhealth.lit.hl7.*
import com.babylonhealth.lit.hl7.model.ElementDefinition.Type
import com.babylonhealth.lit.hl7.model.{DomainResource, ElementDefinition, StructureDefinition}
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.EncoderOps
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import TestHelpers.*

import java.time.{ZoneOffset, ZonedDateTime}

class ProfileGenTest extends AnyFreeSpec with Matchers {

  val definitions = TestHelpers.templates
  given IdProvider = TestIdProvider
  given ReferenceProvider = ReferenceProvider(_ => None)
  given StructureDefinitions = TestStructureDefinitions.structureDefinitions

  lazy val profileGen = ProfileGen(definitions, true)

  "profileGen.makeProfile" - {
    "Simple template" in {
      // Defined ElementDefinitions in two separate objects to reduce the size of the test class init method.
      // Without this you will get "Method too large" compilation errors when using choice fields in a long list.
      def edsPart1 = Seq(
        ElementDefinition(id = Some("Observation.id"), min = Some(1), path = "Observation.id", fixed = Some(choice("123-123-123"))),
        ElementDefinition(
          id = Some("Observation.status"),
          min = Some(1),
          path = "Observation.status",
          fixed = Some(choice(toCode("final")))),
        ElementDefinition(
          id = Some("Observation.code"),
          min = Some(1),
          path = "Observation.code",
          pattern = Some(
            choice(CodeableConcept(coding = LitSeq(Coding(code = Some("ykWNn2DwyB"), system = Some("https://bbl.health")))))
          )
        ),
        ElementDefinition(
          id = Some("Observation.subject"),
          min = Some(0),
          path = "Observation.subject",
          `type` = LitSeq(Type(code = "Reference", targetProfile = LitSeq("http://hl7.org/fhir/StructureDefinition/Patient")))
        ),
        ElementDefinition(
          id = Some("Observation.subject.reference"),
          min = Some(0),
          path = "Observation.subject.reference"
        ),
        ElementDefinition(
          id = Some("Observation.performer"),
          min = Some(0),
          path = "Observation.performer",
          `type` = LitSeq(Type(code = "Reference", targetProfile = LitSeq("http://hl7.org/fhir/StructureDefinition/Practitioner")))
        ),
        ElementDefinition(
          id = Some("Observation.performer.reference"),
          min = Some(0),
          path = "Observation.performer.reference"
        ),
        ElementDefinition(
          id = Some("Observation.effective[x]"),
          path = "Observation.effective[x]",
          `type` = LitSeq(ElementDefinition.Type(code = "dateTime"))
        ),
        ElementDefinition(
          id = Some("Observation.effective[x]:effectiveDateTime"),
          min = Some(1),
          path = "Observation.effectiveDateTime",
          `type` = LitSeq(ElementDefinition.Type(code = "dateTime"))
        ),
        ElementDefinition(
          id = Some("Observation.value[x]:valueQuantity.value"),
          min = Some(0),
          path = "Observation.valueQuantity.value"
        ),
        ElementDefinition(
          id = Some("Observation.value[x]:valueQuantity.unit"),
          min = Some(1),
          path = "Observation.valueQuantity.unit",
          fixed = Some(choice("lbs"))
        ),
        ElementDefinition(
          id = Some("Observation.value[x]:valueQuantity.system"),
          min = Some(1),
          path = "Observation.valueQuantity.system",
          fixed = Some(choice(toUri("http://unitsofmeasure.org")))
        ),
        ElementDefinition(
          id = Some("Observation.value[x]:valueQuantity.code"),
          min = Some(1),
          path = "Observation.valueQuantity.code",
          fixed = Some(choice(toCode("[lb_av]")))
        )
      )
      def edsPart2 = Seq(
        ElementDefinition(id = Some("Observation.contained"), max = Some("0"), path = "Observation.contained"),
        ElementDefinition(id = Some("Observation.text"), max = Some("0"), path = "Observation.text"),
        ElementDefinition(id = Some("Observation.specimen"), max = Some("0"), path = "Observation.specimen"),
        ElementDefinition(id = Some("Observation.referenceRange"), max = Some("0"), path = "Observation.referenceRange"),
        ElementDefinition(id = Some("Observation.partOf"), max = Some("0"), path = "Observation.partOf"),
        ElementDefinition(id = Some("Observation.note"), max = Some("0"), path = "Observation.note"),
        ElementDefinition(id = Some("Observation.modifierExtension"), max = Some("0"), path = "Observation.modifierExtension"),
        ElementDefinition(id = Some("Observation.method"), max = Some("0"), path = "Observation.method"),
        ElementDefinition(id = Some("Observation.language"), max = Some("0"), path = "Observation.language"),
        ElementDefinition(id = Some("Observation.issued"), max = Some("0"), path = "Observation.issued"),
        ElementDefinition(id = Some("Observation.interpretation"), max = Some("0"), path = "Observation.interpretation"),
        ElementDefinition(id = Some("Observation.implicitRules"), max = Some("0"), path = "Observation.implicitRules"),
        ElementDefinition(id = Some("Observation.hasMember"), max = Some("0"), path = "Observation.hasMember"),
        ElementDefinition(id = Some("Observation.focus"), max = Some("0"), path = "Observation.focus"),
        ElementDefinition(id = Some("Observation.extension"), max = Some("0"), path = "Observation.extension"),
        ElementDefinition(id = Some("Observation.encounter"), max = Some("0"), path = "Observation.encounter"),
        ElementDefinition(id = Some("Observation.device"), max = Some("0"), path = "Observation.device"),
        ElementDefinition(id = Some("Observation.derivedFrom"), max = Some("0"), path = "Observation.derivedFrom"),
        ElementDefinition(id = Some("Observation.dataAbsentReason"), max = Some("0"), path = "Observation.dataAbsentReason"),
        ElementDefinition(id = Some("Observation.component"), max = Some("0"), path = "Observation.component"),
        ElementDefinition(id = Some("Observation.category"), max = Some("0"), path = "Observation.category"),
        ElementDefinition(id = Some("Observation.bodySite"), max = Some("0"), path = "Observation.bodySite"),
        ElementDefinition(id = Some("Observation.basedOn"), max = Some("0"), path = "Observation.basedOn")
      )

      val actual = profileGen.makeProfile(TestHelpers.template("BodyWeight"))
      actual shouldEqual StructureDefinition(
        id = Some("BblBodyWeight"),
        url = "https://fhir.bbl.health/StructureDefinition/BblBodyWeight",
        name = "BodyWeight",
        title = Some("Body weight"),
        date = Some(actual.date.get),
        description = Some("Weight innit"),
        meta = Some(Meta(profile = LitSeq("https://fhir.bbl.health/StructureDefinition/BblStructureDefinition"))),
        kind = STRUCTURE_DEFINITION_KIND.RESOURCE,
        `type` = "Observation",
        status = PUBLICATION_STATUS.ACTIVE,
        `abstract` = false,
        derivation = Some(TYPE_DERIVATION_RULE.CONSTRAINT),
        fhirVersion = Some(FHIR_VERSION.`4.0.1`),
        baseDefinition = Some("https://fhir.bbl.health/StructureDefinition/BblObservation"),
        differential = Some(
          StructureDefinition.Differential(element = new NonEmptyLitSeq(edsPart1 ++ edsPart2))
        )
      )
    }

    "Nested type template" in {
      val expected =
        decode[List[SimpleConformanceResourceFile]](slurpRsc("expectedProfiles/nestedConceptProfile.json")).toOption.get.head.rsc.asJson

      val actual = profileGen.makeProfile(TestHelpers.template("NestedConcept"), ZonedDateTime.of(2020, 4, 20, 4, 20, 4, 20, ZoneOffset.UTC)).asJson
      actual shouldEqual expected
    }

    "Template with Int64 field" in {
      val actual = profileGen.makeProfile(TestHelpers.template("PatientInt64"))
      actual.differential.get.element should contain(ElementDefinition(id = Some("Patient.id"), min = Some(1), path = "Patient.id"))
      actual.differential.get.element should contain(ElementDefinition(id = Some("Patient.name"), min = Some(1), path = "Patient.name"))
    }

    "Entire reference tree and inlined resources, valuesets, extensions" in {

      val expected =
        decode[List[SimpleConformanceResourceFile]](slurpRsc("expectedProfiles/healthAssessmentProfile.json")).toOption.get
          .sortBy(_.fileName)
          .asJson
          .asArray
          .get

      val actual = profileGen.makeAllSubProfilesAndValueSets(
        Seq("QuestionnaireResponseFhirBundle"),
        ZonedDateTime.of(2020, 4, 20, 4, 20, 4, 20, ZoneOffset.UTC)
      ).map(SimpleConformanceResourceFile.fromRscFile).sortBy(_.fileName).asJson.asArray.get
      actual shouldEqual expected
    }

    "ValueSet bindings " in {
      val expected =
        decode[List[SimpleConformanceResourceFile]](slurpRsc("expectedProfiles/codingJsonProfile.json")).toOption.get.asJson.asArray.get

      val actual = profileGen.makeAllSubProfilesAndValueSets(
        Seq("CodingJson"),
        ZonedDateTime.of(2020, 4, 20, 4, 20, 4, 20, ZoneOffset.UTC)
      ).map(SimpleConformanceResourceFile.fromRscFile).asJson.asArray.get

      actual should contain theSameElementsAs expected
    }

    "Inherit template profile could be generated" in {
      val expected =
        decode[List[SimpleConformanceResourceFile]](slurpRsc("expectedProfiles/bodyMeasureProfiles.json")).toOption.get.asJson.asArray.get

      val actual = profileGen.makeAllSubProfilesAndValueSets(
        Seq(
          "PatientMetricsBodyMeasure",
          "PatientMetricsBodyMeasureHeightInM",
          "PatientMetricsBodyMeasureWeightInKG",
          "PatientMetricsBodyMeasureBloodGlucose",
          "PatientMetricsBodyMeasureBloodOxygenSaturation"
        ),
        ZonedDateTime.of(2020, 4, 20, 4, 20, 4, 20, ZoneOffset.UTC)
      ).map(SimpleConformanceResourceFile.fromRscFile).asJson.asArray.get

      actual should contain theSameElementsAs expected
    }

    "generates slices" - {
      "Slice for fixed values within nested templates" in {
        val expected =
          decode[List[SimpleConformanceResourceFile]](
            slurpRsc("expectedProfiles/patientProfileProfile.json")).toOption.get.asJson.asArray.get

        val actual = profileGen.makeAllSubProfilesAndValueSets(
          Seq("PatientProfile"),
          ZonedDateTime.of(2020, 4, 20, 4, 20, 4, 20, ZoneOffset.UTC)
        ).map(SimpleConformanceResourceFile.fromRscFile).asJson.asArray.get
        actual should contain theSameElementsAs expected
      }

      "Slice on a coding" in {
        val expected =
          decode[List[SimpleConformanceResourceFile]](
            slurpRsc("expectedProfiles/bloodPressureProfile.json")).toOption.get.asJson.asArray.get

        val actual = profileGen.makeAllSubProfilesAndValueSets(
          Seq("VitalSignsBloodPressure"),
          ZonedDateTime.of(2020, 4, 20, 4, 20, 4, 20, ZoneOffset.UTC)
        ).map(SimpleConformanceResourceFile.fromRscFile).asJson.asArray.get

        actual shouldEqual expected
        actual should contain theSameElementsAs expected
      }

      "Repeated field of custom type containing reference" in {
        val actual = profileGen.makeAllSubProfilesAndValueSets(Seq("TemplateWithReferenceParent"))
        val sd     = actual.head.rsc.asInstanceOf[StructureDefinition]
        val el     = sd.differential.get.element.find(el => "CarePlan.contributor".equals(el.path))
        el.isDefined shouldBe true
        el.get.min.isDefined shouldBe true
        el.get.min.get shouldBe 0
      }

      "on an extension" in {
        val sd = profileGen.makeProfile(
          definitions("FlatQuestionnaireResponse").toTry.get.asTemplate.get,
          ZonedDateTime.of(2020, 4, 20, 4, 20, 4, 20, ZoneOffset.UTC)
        )

        val differentialIds = sd.differential.toList.flatMap(_.element).flatMap(_.id)
        differentialIds should contain("QuestionnaireResponse.item.extension:BblQuestionnaireResponseExtensionSource")
        atLeast(1, differentialIds) should startWith(
          "QuestionnaireResponse.item.extension:BblQuestionnaireResponseExtensionAmmended.extension:extensionsCanContainExtensions")
      }
    }
  }
}

case class SimpleConformanceResourceFile(fileName: String, rsc: DomainResource)
object SimpleConformanceResourceFile {
  def fromRscFile(f: ConformanceResourceFile) = SimpleConformanceResourceFile(f.path.toString, f.rsc)
}
