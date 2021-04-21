import java.io.StringWriter
import java.io.PrintWriter
import java.io.File
import scala.jdk.CollectionConverters._
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import overflowdb.{Config, Edge, Node}
import io.shiftleft.codepropertygraph.cpgloading.{CpgLoader, CpgLoaderConfig}
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.semanticcpg.layers.{LayerCreator, LayerCreatorContext, Scpg}
import io.shiftleft.dataflowengineoss.layers.dataflows.OssDataFlow
import io.shiftleft.dataflowengineoss.layers.dataflows.OssDataFlowOptions
import org.slf4j.{Logger, LoggerFactory}
import scopt.OParser
import overflowdb.traversal._


case class Args(
   loadDefaultOverlays: Boolean = true,
   loadOssDataflowOverlay: Boolean = true,
   loadCpg: File = new File("."),
   json: File = new File("."),
 )


object Main {
  private val logger: Logger = LoggerFactory.getLogger(Main.getClass)

  def convertNode(node: Node) : Map[String, Object] = {
    node.propertyMap.asScala.addAll(List(
        ("TYPE", node.label),
        ("ID", node.id().asInstanceOf[Object])
        )).toMap
  }

  def convertEdge(edge: Edge) : Map[String, Object] = {
    edge.propertyMap.asScala.addAll(List(
        ("TYPE", edge.label),
        ("outV", edge.inNode.id()),
        ("inV", edge.outNode.id())
    )).toMap
  }

  // source: io.shiftleft.codepropertygraph:console/src/main/scala/io/shiftleft/console/Console.scala
  def applyDefaultOverlays(cpg: Cpg): Cpg = {
    val appliedOverlays = io.shiftleft.semanticcpg.Overlays.appliedOverlays(cpg)
    if (appliedOverlays.isEmpty && !(new Scpg().probe(cpg))) {
      logger.info("Adding default overlays...")
      val overlayCreators = List(new Scpg)
      _runAnalyzer(cpg, overlayCreators: _*)
    }
    cpg
  }

  // source: io.shiftleft.codepropertygraph:console/src/main/scala/io/shiftleft/console/Console.scala
  def _runAnalyzer(cpg: Cpg, overlayCreators: LayerCreator*): Cpg = {
    overlayCreators.foreach { creator =>
      runCreator(cpg, creator)
    }
    cpg
  }

  // source: io.shiftleft.codepropertygraph:console/src/main/scala/io/shiftleft/console/Console.scala
  protected def runCreator(cpg: Cpg, creator: LayerCreator): Unit = {
    logger.info(s"Applying overlay: ${creator.overlayName}")
    val context = new LayerCreatorContext(cpg)
    creator.run(context)
  }

  def loadCpg(cpgFilename: File): Cpg = {
    // source: io.shiftleft.codepropertygraph:console/src/main/scala/io/shiftleft/console/Console.scala
    val odbConfig = Config.withDefaults.withStorageLocation(cpgFilename.toString)
    val config = CpgLoaderConfig.withDefaults.doNotCreateIndexesOnLoad.withOverflowConfig(odbConfig)
    val cpg = CpgLoader.loadFromOverflowDb(config)
    CpgLoader.createIndexes(cpg)
    cpg
  }

  def runOssDataflow(cpg: Cpg): Unit = {
    logger.info("Applying oss dataflow overlay...")
    val context = new LayerCreatorContext(cpg)
    val opts = new OssDataFlowOptions()
    new OssDataFlow(opts).run(context)
  }

  def exportCpg(configArgs: Args) : Unit = {
    val cpgFilename = configArgs.loadCpg
    logger.info(s"Load cpg from file: ${cpgFilename}")
    val cpg = loadCpg(cpgFilename)

    if (cpg.graph.E.asScala.hasLabel("AST").count.next() == 0) {
      logger.error("Loaded graph does not contain any AST edges; Exit")
      sys.exit(1)
    }

    val has_default_overlay = cpg.graph.E.asScala.hasLabel("CDG").count.next() != 0
    val has_oss_dataflow = cpg.graph.E.asScala.hasLabel("REACHING_DEF").count.next() != 0

    if (configArgs.loadDefaultOverlays) {
      if (!has_default_overlay) {
        applyDefaultOverlays(cpg)
        // creates edges: CDG, ALIAS_OF, CONTAINS, DOMINATE, EVAL_TYPE, SOURCEFILE, POST_DOMINATE, PARAMETER_LINK
      } else {
        logger.info("Graph seems to have default overlays already applied; Nothing to do")
      }
    } else {
      logger.info("Default overlays will not be applied")
    }

    if (configArgs.loadOssDataflowOverlay) {
      if (!has_oss_dataflow) {
        if (has_default_overlay) {
          runOssDataflow(cpg)
          // creates edges: REACHING_DEF
        } else {
          logger.warn("oss dataflow overlay depends on default overlays; skip oss dataflow overlay")
        }
      } else {
        logger.info("Graph seems to have the oss dataflow overlay already applied; Nothing to do")
      }
    } else {
      logger.info("oss dataflow overlay will not be applied")
    }


    // use gremlin to iterate edges and nodes
    logger.info(s"Export graph to json file: ${configArgs.json.toString}")
    val g = cpg.graph;
    val nodes = g.V.asScala.toList.map(convertNode)
    val edges = g.E.asScala.toList.map(convertEdge)
    val m = Map("nodes" -> nodes, "edges" -> edges)

    val mapper = JsonMapper.builder()
      .addModule(DefaultScalaModule)
      .build()
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val out = new StringWriter
    mapper.writerWithDefaultPrettyPrinter().writeValue(out, m)
    val json = out.toString
    new PrintWriter(configArgs.json) { write(json); close() }

    cpg.close()
  }

  def main(args: Array[String]) : Unit = {
    val builder = OParser.builder[Args]
    val parser1 = {
      import builder._
      OParser.sequence(
        programName("joernCpgExport"),
        opt[Unit]('d', "no-default-overlays")
          .action((_, c) => c.copy(loadDefaultOverlays = false))
          .text("do not apply default overlays"),
        opt[Unit]('o', "no-oss-dataflow")
          .action((_, c) => c.copy(loadOssDataflowOverlay = false))
          .text("do not apply oss dataflow overlay"),
        opt[File]('c', "cpg")
          .valueName("<cpg.bin>")
          .required()
          .action((x, c) => c.copy(loadCpg = x))
          .text("load cpg from OverflowDB"),
        opt[File]('j', "json")
          .required()
          .valueName("<cpg.json>")
          .action((x, c) => c.copy(json = x))
          .text("export cpg as json file"),
        help("help") text("prints this usage text"),
      )
    }

    OParser.parse(parser1, args, Args()) match {
      case Some(config) =>
        exportCpg(config)
      case _ =>
        sys.exit(1)
    }
  }
}