package com.emed.hydrant.proptest

import com.emed.hydrant.*
import com.babylonhealth.lit.core.DecoderParams
import io.circe.Json
import org.scalatest.freespec.AsyncFreeSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, Succeeded}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.concurrent.Future

trait TemplateTesting extends AsyncFreeSpecLike with Matchers with ScalaCheckDrivenPropertyChecks {


  def allPass(ass: Seq[Assertion]): Assertion = {
    val failed = ass.filterNot(_ == Succeeded)
    if (failed.isEmpty) succeed else failed.head
  }
  // Bound the size of generated lists to stop these tests taking forever
  override implicit val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 120, sizeRange = 20)

  lazy val definitions: Definitions = Definitions.fromClassPath()

  lazy val generator = DFhirGenerator(definitions)

  def dehydratedGen(defn: HydrationDefinition) = generator.dehydratedGen(defn)

  lazy val hydration = Hydration(definitions)
  lazy val dehydration = Dehydration(definitions)

  implicit val decoderParams: DecoderParams = DecoderParams(tolerateProfileErrors = false, flexibleCardinality = false, ignoreUnknownFields = false)

  lazy val excludeRequireFieldCheck: Set[String] = Set.empty

  val definitionTests: PartialFunction[HydrationDefinition, Unit]

  lazy val testFilter: HydrationDefinition => Boolean = _ => true

  def run() = try {
    definitions.all.map(_.id).foreach(println)
    definitions.all.collect {
      case h if testFilter(h) && definitionTests.isDefinedAt(h) =>
        s"${h.name} (${h.id})" - definitionTests(h) // Test grouped by template name
    }
  } catch {
    case e: Throwable =>
      println("Failed to register property tests")
      throw e
  }

  def withHydrated[T](template: Template, dehydrated: Json)(f: Json => T): T = {
    val hydrated = hydration.hydrateUnsafe(template, dehydrated)
    try {
      f(hydrated)
    } catch {
      case e: Throwable => fail(f"${e.getMessage}\nGiven dehydrated input: \n$dehydrated\nAnd hydrated output:\n$hydrated", e)
    }
  }

}
