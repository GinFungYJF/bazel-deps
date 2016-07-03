package com.github.johnynek.bazel_deps

import java.io.File
import cats.data.Xor

object MakeDeps {
    def subprojects(language: Language, projPart: String, subs: List[String], version: String) = {
      val (g, p) = projPart.split(':') match {
        case Array(g, p) =>
          require(p.last == '-', s"project must end with '-' found: $p")
          (g, p.dropRight(1))
        case _ => sys.error(s"expected one ':', found: $projPart")
      }
      MavenGroup(g) ->
        Map(ArtifactOrProject(p) ->
          ProjectRecord(
            language,
            Version(version),
            Some(subs.map(Subproject(_)))))
    }
    def java(dep: String) =
      dep.split(':') match {
        case Array(g, a, v) =>
          MavenGroup(g) ->
            Map(ArtifactOrProject(a) ->
              ProjectRecord(
                Language.Java,
                Version(v),
                None))
        case _ => sys.error(s"expect two colons, got: $dep")
      }

    def scala(dep: String, mangle: Boolean = true) =
      dep.split(':') match {
        case Array(g, a, v) =>
          MavenGroup(g) ->
            Map(ArtifactOrProject(a) ->
              ProjectRecord(
                Language.Scala(Version("2.11"), mangle),
                Version(v),
                None))
        case _ => sys.error(s"expect two colons, got: $dep")
      }
}

trait MakeDeps {
  def getSettings(args: Array[String]): (Model, String, String)

  def main(args: Array[String]): Unit = {
    val (model, workspacePath, projectRoot) = getSettings(args)
    val thirdParty = model.getOptions.getThirdPartyDirectory
    val deps = model.dependencies
    val resolver = new Resolver(model.getOptions.getResolvers)
    val graph = resolver.addAll(Graph.empty, deps.roots, model.getReplacements)
    // This is a defensive check that can be removed as we add more tests
    deps.roots.foreach { m => require(graph.nodes(m), s"$m") }

    Normalizer(graph, deps, model.getOptions) match {
      case None =>
        println("[ERROR] could not normalize versions:")
        println(graph.nodes.groupBy(_.unversioned)
          .mapValues { _.map(_.version).toList.sorted }
          .filter { case (_, s) => s.lengthCompare(1) > 0 }
          .map { case (u, vs) => s"""${u.asString}: ${vs.mkString(", ")}\n""" }
          .mkString("\n"))
        System.exit(1)
      case Some(normalized) =>
        /**
         * The graph is now normalized, lets get the shas
         */
        val duplicates = graph.nodes
          .groupBy(_.unversioned)
          .mapValues(_.map(_.version))
          .filter { case (_, set) => set.size > 1 }

        val shas = resolver.getShas(normalized.nodes)
        // build the workspace
        val ws = Writer.workspace(normalized, duplicates, shas, model)
        // build the BUILDs in thirdParty
        val targets = Writer.targets(normalized, thirdParty, model)
        val io = for {
          _ <- IO.write(new File(workspacePath), ws.getBytes("UTF-8"))
          _ <- Writer.createBuildFiles(new File(projectRoot), targets)
        } yield ()

        // Here we actually run the whole thing
        io.foldMap(IO.fileSystemExec) match {
          case Xor.Left(err) =>
            System.err.println(err)
            System.exit(-1)
          case Xor.Right(_) =>
            println(s"wrote ${targets.size} targets")
        }
    }
  }
}
