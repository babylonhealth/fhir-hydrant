package com.emed.hydrant.proptest

import cats.implicits.*
import com.babylonhealth.lit.core.serdes.objectEncoder
import com.babylonhealth.lit.core.toCanonical
import com.babylonhealth.lit.hl7.ISSUE_TYPE
import com.emed.hydrant.*
import io.circe.Json
import io.circe.syntax.*
import org.junit.runner.RunWith
import org.scalatest.Assertion
import org.scalatestplus.junit.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

/** Property based testing of templates using a FHIR validator. By default runs against definitions on the classpath. You can extend to set
  * your own definitions and testFilter. Extend with an implementation of validator which implements FHIR validation (e.g. with an external
  * conformance server). Likely you will need to run profile generation to generate profiles first, upload them to your conformance server,
  * then run these.
  */
@RunWith(classOf[JUnitRunner])
abstract class ConformancePropsTest extends TemplateTesting {

  val validator: ConformanceValidator

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 50, sizeRange = 10)

  def metaProfiles(template: Template) = {
    for {
      meta         <- template.hydrated("meta")
      p            <- meta("profile")
      metaProfiles <- p.asArray
    } yield metaProfiles
  }.getOrElse(Vector.empty)

  private def extractResourceType(resource: Json): Option[String] = {
    for {
      obj              <- resource.asObject
      resourceTypeJson <- obj("resourceType")
      tpe              <- resourceTypeJson.asString
    } yield tpe
  }

  def validate(hydratedFile: Json): Future[Seq[ValidationFailure]] =
    hydratedFile.hcursor
      .downField("meta")
      .downField("profile")
      .focus
      .flatMap(_.asArray)
      .flatMap(_.traverse(_.asString))
      .orElse(
        extractResourceType(hydratedFile)
          .map(resourceType => Vector(s"http://hl7.org/fhir/StructureDefinition/$resourceType"))
      )
      .map(profileUrls => validator.validate(hydratedFile, profileUrls))
      .getOrElse(
        Future.successful(Seq(ValidationFailure("resourceType field is required")))
      )

  def doValidation(hydrated: Json) = {
    val hydratedResources = hydrated.asArray.getOrElse(Vector(hydrated))
    hydratedResources.map(validate).flatMap(Await.result(_, 10.seconds))
  }

  override val definitionTests: PartialFunction[HydrationDefinition, Unit] = {
    case template: Template if template.hydrated.asObject.flatMap(_("resourceType")).isDefined =>
      s"Conforms to all expected profiles" in {
        forAll(dehydratedGen(template)) { dehydrated =>
          withHydrated(template, dehydrated) { hydrated =>
            val validationFailures = doValidation(hydrated)

            if (validationFailures.nonEmpty)
              fail(s"""Resource ${hydrated.name} is INVALID for profile(s):
                 |${validationFailures.mkString("\n")}
                 |
                 |Given dehydrated input:
                 |$dehydrated
                 |And hydrated output:
                 |$hydrated
                 |
                 |""".stripMargin)
            else succeed
          }
        }
      }
  }

  run()
}
