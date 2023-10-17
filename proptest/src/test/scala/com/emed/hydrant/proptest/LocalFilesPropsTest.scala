package com.emed.hydrant.proptest

import com.emed.hydrant.Definitions
import com.emed.hydrant.HydrationDefinition

/** Runs the property test against local template files. */
class LocalFilesPropsTest extends HydrationPropsTest {

  // Modify for a different folder
  override lazy val definitions: Definitions = Definitions.fromFolderPath("../dfhir-templates")

  // Put template ids to test in here
  lazy val testTemplateIds: Set[String] = Set()

  // Comment out the line below to test all
  override lazy val testFilter: HydrationDefinition => Boolean = testTemplateIds contains _.id

}
