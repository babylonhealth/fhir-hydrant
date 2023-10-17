package com.emed.hydrant.profilegen

import com.emed.hydrant.*
import com.babylonhealth.lit.core.{FHIRDateTime, FHIRDateTimeSpecificity, LitSeq, toCode, toMarkdown, toUri}
import com.babylonhealth.lit.hl7.*
import com.babylonhealth.lit.hl7.model.ValueSet
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import com.emed.hydrant.TestHelpers.*

import java.time.ZonedDateTime

class ValueSetGenTest extends AnyFreeSpec with Matchers with ValueSetGen {

  override val definitions: Definitions = templates
  override val structureDefinitions = TestStructureDefinitions.structureDefinitions
  given FhirTypeProvider = structureDefinitions
  given ReferenceProvider = ReferenceProvider(_ => None)
  override val idProvider = TestIdProvider
  override val hydrate = Hydration(definitions)

  "make bbl ValueSet" in {
    val date   = ZonedDateTime.now()
    val actual = makeValueSets("BblCodingCodeValues", enumDef("CodingCodeValues"), date)
    val codingValueSet = ValueSet(
      id = Some("BblCodingCodeValues"),
      url = Some("https://fhir.bbl.health/ValueSet/BblCodingCodeValues"),
      name = Some(s"CodingCodeValues"),
      description = Some("the three codes"),
      title = Some(s"Coding code"),
      status = PUBLICATION_STATUS.ACTIVE,
      date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
      compose = Some(
        ValueSet.Compose(
          include = LitSeq.nonempty(
            ValueSet.Compose.Include(
              system = Some("https://bbl.health"),
              concept = LitSeq(
                ValueSet.Compose.Include.Concept(code = "420420"),
                ValueSet.Compose.Include.Concept(code = "696969"),
                ValueSet.Compose.Include.Concept(code = "331337")
              )
            )
          )
        )
      )
    )

    actual shouldEqual codingValueSet
  }

  "make hl7 ValueSet" in {
    val date   = ZonedDateTime.now()
    val actual = makeValueSets("BblObservationStatusEnum", TestHelpers.enumDef("ObservationStatusEnum"), date)
    val codeValueSet = ValueSet(
      id = Some("BblObservationStatusEnum"),
      url = Some("https://fhir.bbl.health/ValueSet/BblObservationStatusEnum"),
      name = Some("ObservationStatusEnum"),
      title = Some("Observation Status Enum"),
      description = Some("The status of Observation"),
      status = PUBLICATION_STATUS.ACTIVE,
      date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
      compose = Some(
        ValueSet.Compose(
          include = LitSeq.nonempty(
            ValueSet.Compose.Include(
              system = Some("https://bbl.health"),
              concept = LitSeq(
                ValueSet.Compose.Include.Concept(code = "final"),
                ValueSet.Compose.Include.Concept(code = "amended")
              )
            )
          )
        )
      )
    )
    actual shouldEqual codeValueSet
  }

  "make bbl ValueSet from json enum" in {
    val date   = ZonedDateTime.now()
    val actual = makeValueSets("BblObservationCodeJsonEnum", TestHelpers.enumDef("ObservationCodeJsonEnum"), date)
    val codeJsonValueSet = ValueSet(
      id = Some("BblObservationCodeJsonEnum"),
      url = Some("https://fhir.bbl.health/ValueSet/BblObservationCodeJsonEnum"),
      name = Some(s"ObservationCodeJsonEnum"),
      title = Some(s"Observation Code Json Enum"),
      status = PUBLICATION_STATUS.ACTIVE,
      description = Some("Observations code as Jsons"),
      date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
      compose = Some(
        ValueSet.Compose(
          include = LitSeq.nonempty(
            ValueSet.Compose.Include(
              system = Some("https://bbl.health"),
              concept = LitSeq(
                ValueSet.Compose.Include.Concept(code = "420420", display = Option("blaze")),
                ValueSet.Compose.Include.Concept(code = "696969", display = Option("cheeky"))
              )
            )
          )
        )
      )
    )
    actual shouldEqual codeJsonValueSet
  }

  "make bbl ValueSet from json enum with multiple systems" in {
    val date   = ZonedDateTime.now()
    val actual = makeValueSets("BblObservationBodySiteEnum", TestHelpers.enumDef("ObservationBodySiteEnum"), date)
    val codeJsonValueSet = ValueSet(
      id = Some("BblObservationBodySiteEnum"),
      url = Some("https://fhir.bbl.health/ValueSet/BblObservationBodySiteEnum"),
      name = Some(s"ObservationBodySiteEnum"),
      title = Some(s"Observation BodySite Enum"),
      description = Some("The body site of the Observation"),
      status = PUBLICATION_STATUS.ACTIVE,
      date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
      compose = Some(
        ValueSet.Compose(
          include = LitSeq.nonempty(
            ValueSet.Compose.Include(
              system = Some("https://bbl.health"),
              concept = LitSeq(
                ValueSet.Compose.Include.Concept(code = "vN2BhNIPwg")
              )
            ),
            ValueSet.Compose.Include(
              system = Some("http://snomed.info/sct"),
              concept = LitSeq(
                ValueSet.Compose.Include.Concept(code = "774007")
              )
            )
          )
        )
      )
    )
    actual shouldEqual codeJsonValueSet
  }

  "make bbl ValueSet from json enum with different absent system" in {
    val date   = ZonedDateTime.now()
    val actual = makeValueSets("BblObservationCodeJsonEnumAbsent", TestHelpers.enumDef("ObservationCodeJsonEnumAbsent"), date)
    val codeJsonValueSet = ValueSet(
      id = Some("BblObservationCodeJsonEnumAbsent"),
      url = Some("https://fhir.bbl.health/ValueSet/BblObservationCodeJsonEnumAbsent"),
      name = Some(s"ObservationCodeJsonEnumAbsent"),
      title = Some(s"Observation Code Json Enum Absent"),
      status = PUBLICATION_STATUS.ACTIVE,
      description = Some("Observations code as Jsons also absent value"),
      date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
      compose = Some(
        ValueSet.Compose(
          include = LitSeq.nonempty(
            ValueSet.Compose.Include(
              system = Some("https://bbl.health"),
              concept = LitSeq(
                ValueSet.Compose.Include.Concept(code = "🌲", display = Option("drug culture reference"))
              )
            ),
            ValueSet.Compose.Include(
              system = Some("http://snomed.info/sct"),
              concept = LitSeq(
                ValueSet.Compose.Include.Concept(code = "420420", display = Option("blaze")),
                ValueSet.Compose.Include.Concept(code = "696969", display = Option("cheeky"))
              )
            )
          )
        )
      )
    )
    actual shouldEqual codeJsonValueSet
  }

  "make child template valueset for code field" in {
    val date   = ZonedDateTime.now()
    val actual = makeChildTemplateCodingValueSets(TestHelpers.template("PatientMetricsBodyMeasure"), TestHelpers.templates, date, None)
    val childValueSet = Vector(
      ValueSet(
        id = Some("BblPatientMetricsBodyMeasureCode"),
        url = Some("https://fhir.bbl.health/ValueSet/BblPatientMetricsBodyMeasureCode"),
        name = Some("Code"),
        title = Some("Body Measure code Enum"),
        description = Some("The valueset for Body Measure code"),
        status = PUBLICATION_STATUS.ACTIVE,
        date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
        compose = Some(
          ValueSet.Compose(
            include = LitSeq.nonempty(
              ValueSet.Compose.Include(
                system = Some("https://bbl.health"),
                concept = LitSeq(
                  ValueSet.Compose.Include.Concept(code = "BXPPBhmkeC", display = Some("Blood Glucose")),
                  ValueSet.Compose.Include.Concept(code = "iWW1T2ld21", display = Some("Blood Oxygen Saturation")),
                  ValueSet.Compose.Include.Concept(code = "D_ViDKoBWF", display = Some("Body height")),
                  ValueSet.Compose.Include.Concept(code = "ykWNn2DwyB", display = Some("Body weight"))
                )
              )
            )
          )
        )
      ),
      ValueSet(
        id = Some("BblPatientMetricsBodyMeasureInterpretation"),
        url = Some("https://fhir.bbl.health/ValueSet/BblPatientMetricsBodyMeasureInterpretation"),
        name = Some("Interpretation"),
        date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
        title = Some("Body Measure interpretation Enum"),
        status = PUBLICATION_STATUS.ACTIVE,
        description = Some("The valueset for Body Measure interpretation"),
        compose = Some(
          ValueSet.Compose(include = LitSeq.nonempty(
            ValueSet.Compose.Include(
              system = Some("https://bbl.health"),
              concept = LitSeq(
                ValueSet.Compose.Include.Concept(code = "9rTncufFB3", display = Some("Exercise physically impossible"))
              )
            ))))
      ),
      ValueSet(
        id = Some("BblPatientMetricsBodyMeasureCategory"),
        url = Some("https://fhir.bbl.health/ValueSet/BblPatientMetricsBodyMeasureCategory"),
        name = Some("Category"),
        date = Some(FHIRDateTime(date, FHIRDateTimeSpecificity.Day)),
        title = Some("Body Measure category Enum"),
        status = PUBLICATION_STATUS.ACTIVE,
        description = Some("The valueset for Body Measure category"),
        compose = Some(
          ValueSet.Compose(include = LitSeq(ValueSet.Compose.Include(
            system = Some("http://terminology.hl7.org/CodeSystem/observation-category"),
            concept = LitSeq(ValueSet.Compose.Include.Concept(code = "vital-signs", display = Some("Vital Signs")))
          ))))
      )
    )
    actual should contain theSameElementsAs childValueSet

  }

  "Error for enum that should not be a ValueSet" in {
    val date = ZonedDateTime.now()
    val defn = TestHelpers.enumDef("ObservationValueQuantityEnum")
    val thrown = intercept[Error] {
      makeValueSets("ObservationValueQuantityEnum", defn, date)
    }
    thrown shouldBe a[EnumDefinitionValueError]
  }

  override def paramNameByType: Map[String, String] = Map.empty
}
