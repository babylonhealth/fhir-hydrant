package com.emed.hydrant.proptest

import cats.implicits.*
import com.emed.hydrant.{Definitions, HydrationDefinition}
import io.circe.Json
import org.scalatest.DoNotDiscover

import scala.concurrent.Future

/** Runs the property test against local template files. */
@DoNotDiscover
class LocalFilesConformancePropsTest extends ConformancePropsTest {

  // Always passes. Implement this!
  override val validator: ConformanceValidator = (_: Json, _: Seq[String]) => Future.successful(Nil)

  // Modify for a different folder
  override lazy val definitions: Definitions = Definitions.fromFolderPath("../dfhir-templates")

  // Put template ids to test in here
  lazy val testTemplateIds: Set[String] = Set()

  // Comment out the line below to test all
  override lazy val testFilter: HydrationDefinition => Boolean = testTemplateIds contains _.id
}
