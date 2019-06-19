package se.kth.cda.compiler

import se.kth.cda.arc.syntaxtree.{ Type => ArcType, _ }
import se.kth.cda.compiler.dataflow._
import scala.util.{ Try, Success, Failure }

object ASTDataFlowConverter {
  import AST.ExprKind;

  def transform(input: AST.ArcStatements): DFGraph = {
    val (args, body) = extractArgs(input.exprs.head);
    val dfg = transformExpr(body, args);
    dfg
  }

  def extractArgs(expr: AST.Expr): (Map[String, ArcArg], AST.Expr) = {
    import AST.ExprKind._;

    expr.kind match {
      case Lambda(params, body) => {
        val args = params.zipWithIndex.flatMap{ case (p, i) => ArcArg.fromParam(i, p).toOption };
        val map = args.map(a => (a.symbol.name -> a)).toMap;
        (map, body)
      }
      case _ => ???
    }
  }

  private def transformExpr(expr: AST.Expr, args: Map[String, ArcArg]): DFGraph = {
    import AST.ExprKind._;
    import AST.IterKind;

    println(args);

    expr.kind match {
      case For(iterator, builder, body) => iterator.kind match {
        case IterKind.KeyByIter => {
          ???
        }
        case IterKind.NextIter | IterKind.UnknownIter => { // TODO don't have unkown iter here!!!
          builder.ty match {
            case Types.Builders.StreamAppender(elemTy, _) => {
              body.kind match {
                case Lambda(params, mapBody) => {
                  val builderName = params(0).symbol;
                  val builderInit = builder.kind.asInstanceOf[Ident].symbol;
                  val valueParam = params(2);

                  val weldExpr = mapBodyToWeld(valueParam, mapBody);
                  val weldProgram = AST.Program(macros = Nil, weldExpr, null);

                  val output = args(builderInit.name).asInstanceOf[ArcArg.SinkArg];
                  //assert(output.isInstanceOf[ArcArg.SinkArg]);
                  val input = (iterator.data.kind match {
                    case Ident(symb) => args(symb.name)
                    case _           => ???
                  }).asInstanceOf[ArcArg.SourceArg];
                  val sink = Sink.empty;
                  val e2 = Edge.forward(Type.fromArc(output.ty), null, sink);
                  val mapper = NodeTemplate.Map.withBody(weldProgram).copy(outputs = List(e2));
                  e2.from = mapper;
                  val e1 = Edge.forward(Type.fromArc(input.ty), null, mapper);
                  mapper.inputs = List(e1);
                  val source = Source.withOut(e1);
                  e1.from = source;
                  sink.in = List(e2);

                  Graph(List(source))
                }
                case _ => ???
              }
            }
            case _: Types.Builders.Windower => {
              ??? // TODO
            }
            case _ => ???
          }
        }
        case _ => ???
      }
      case _ => ???
    }
  }

  private def mapBodyToWeld(valueParam: AST.Parameter, in: AST.Expr): AST.Expr = {
    import AST.ExprKind._;

    in.kind match {
      case Merge(builder, body) => {
        val newSymb = AST.Symbol.unknown;
        Lambda(
          Vector(valueParam),
          Let(newSymb, body.ty, body, Ident(newSymb).toExpr(body.ty)).toExpr(body.ty)).toExpr(body.ty)
      }
    }
  }
}

sealed trait ArcArg {
  def symbol: AST.Symbol;
}
object ArcArg {
  case class SourceArg(pos: Int, symbol: AST.Symbol, ty: ArcType) extends ArcArg
  case class SinkArg(pos: Int, symbol: AST.Symbol, ty: ArcType) extends ArcArg

  def fromParam(pos: Int, p: AST.Parameter): Try[ArcArg] = Try {
    p.ty match {
      case stream: Types.Stream                     => SourceArg(pos, p.symbol, stream.elemTy)
      case streamapp: Types.Builders.StreamAppender => SinkArg(pos, p.symbol, streamapp.elemTy)
      case _                                        => ???
    }
  }
}
