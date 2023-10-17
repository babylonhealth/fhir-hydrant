inThisBuild(
  List(
    organization    := "com.emed.hydrant",
    homepage        := Some(url("https://github.com/babylonhealth/fhir-hydrant")),
    licenses        := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    publishArtifact := true,
    developers := List(
      Developer(
        "DrGreggles",
        "Gregory McKay",
        "gregorymckay@protonmail.com",
        url("https://github.com/DrGreggles")
      ),
      Developer(
        "aelred",
        "Felix Chapman",
        "felix.chapman@babylonhealth.com",
        url("https://github.com/aelred")
      )
    ),
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository     := "https://s01.oss.sonatype.org/service/local"
  ))

val circeVersion   = "0.14.5"
val catsVersion    = "2.9.0"
val litVersion     = "0.14.17"
val scalatestV     = "3.2.16"
val scalatestplusV = "3.2.17.0"
val scalacheckV    = "1.17.0"

val commonSettings = Seq(
  scalaVersion := "3.3.0",
  organization := "com.emed.hydrant",
  version      := "1.0.0",
  scalacOptions ++= Seq("-Xfatal-warnings", "-deprecation", "-feature"),
  scalacOptions ++= Seq("-Xmax-inlines", "100"),
  libraryDependencies ++= Seq(
    "org.typelevel"       %% "cats-core"     % catsVersion,
    "io.circe"            %% "circe-core"    % circeVersion,
    "io.circe"            %% "circe-generic" % circeVersion,
    "io.circe"            %% "circe-parser"  % circeVersion,
    "io.circe"            %% "circe-yaml"    % "0.14.2",
    "io.github.classgraph" % "classgraph"    % "4.8.162",
    "org.scalatest"       %% "scalatest"     % scalatestV % Test
  )
)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)

lazy val profileGen = project
  .in(file("profilegen"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.babylonhealth.lit" %% "hl7" % litVersion
    )
  )
  .dependsOn(core % "compile->compile;test->test")

// Runs property tests against all templates on the classpath.
lazy val proptest = project
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.babylonhealth.lit" %% "hl7"                 % litVersion,
      "org.scalatest"         %% "scalatest"           % scalatestV,
      "org.scalatest"         %% "scalatest"           % scalatestV,
      "org.scalacheck"        %% "scalacheck"          % scalacheckV,
      "org.scalatestplus"     %% "scalacheck-1-17"     % scalatestplusV,
      "org.scalatestplus"     %% "junit-4-13"          % scalatestplusV,
      "junit"                  % "junit"               % "4.12",
      "com.github.cb372"      %% "scalacache-caffeine" % "1.0.0-M6"
    )
  )
  .dependsOn(core)
