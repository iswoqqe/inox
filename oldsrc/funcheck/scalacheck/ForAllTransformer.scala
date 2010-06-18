package funcheck.scalacheck

import scala.tools.nsc.transform.TypingTransformers
import scala.tools.nsc.util.NoPosition
import funcheck.util.FreshNameCreator 

/** Takes care of mapping Specs.forAll methods calls to
 * ScalaCheck org.scalacheck.Prop.forAll.
 */
trait ForAllTransformer extends TypingTransformers
  with ScalaCheck
  with FreshNameCreator 
{ 
  import global._
  
  private lazy val specsModule: Symbol = definitions.getModule("funcheck.lib.Specs")
  
  
  
  def forAllTransform(unit: CompilationUnit): Unit = 
    unit.body = new ForAllTransformer(unit).transform(unit.body)
  
    
        
    
  class ForAllTransformer(unit: CompilationUnit) 
    extends TypingTransformer(unit) 
  { 
    
    override def transform(tree: Tree): Tree = {
      curTree = tree
       
      tree match {
        /* XXX: This only works for top-level forAll. Nested forAll are not handled by the current version*/
        case Apply(TypeApply(s: Select, _), rhs @ List(f @ Function(vparams,body))) if isSelectOfSpecsMethod(s.symbol, "forAll") =>
          atOwner(currentOwner) {
            assert(vparams.size == 1, "funcheck.Specs.forAll properties are expected to take a single (tuple) parameter")
            
            val v @ ValDef(mods,name,vtpt,vinit) = vparams.head
            
            vtpt.tpe match {
              // the type of the (single, by the above assumption) function parameter 
              // will tell us what are the generators needed. In fact, we need to manually 
              // provide the generators since despite the generators that we create are 
              // implicit definitions, funcheck is hooking after the typechecking phase 
              // and implicit definition are solved at typechecking. Therefore the need 
              // of manually provide every single parameter to the org.scalacheck.Prop.forAll
              // method. 
              // This is actually one of the major limitations of this plugin since it is not 
              // really quite flexible. For a future work it might be a good idea to rethink 
              // how this problem can be fixed (an idea could be to inject the code and actuate 
              // the forall conversion and then typecheck the whole program from zero).
              case tpe @ TypeRef(_,value,vtpes) =>
                var fun: Function = {
                  if(vtpes.size <= 1) {
                    // if there is less than one parameter then the function tree can be injected 
                    // without (almost) no modificcation because it matches what Scalacheck Prop.forAll
                    // expects
                    f
                  } 
                  else {
                    // Transforming a pair into a list of arguments (this is what ScalaCheck Prop.forAll expects)
                    
                    // create a fresh name for each parameter declared parametric type
                    val freshNames = vtpes.map(i =>  fresh.newName(NoPosition,"v"))
                    
                    val funSym = tree.symbol
                    
                    val subst = for { i <- 0 to vtpes.size-1} yield {
                      val toSym = funSym.newValueParameter(funSym.pos, freshNames(i)).setInfo(vtpes(i))
                      
                      val from = Select(v, v.symbol.tpe.decl("_"+(i+1)))
                      val to =  ValDef(toSym, EmptyTree) setPos (tree.pos)                        
                      
                      (from, to)
                    } 
                      
                      
                    val valdefs = subst.map(_._2).toList
                    val fun = localTyper.typed {
                      val newBody = new MyTreeSubstituter(subst.map(p => p._1.symbol).toList, valdefs.map(v => Ident(v.symbol)).toList).transform(resetAttrs(body))
                      Function(valdefs, newBody)
                    }.asInstanceOf[Function]
                      
                      
                    new ChangeOwnerTraverser(funSym, fun.symbol).traverse(fun);
                    new ForeachTreeTraverser({t: Tree => t setPos tree.pos}).traverse(fun)
                    fun
                  }
                }
              
                // Prop.forall(function , where function is of the form (v1,v2,...,vn) => expr(v1,v2,..,vn))   
                val prop = Prop.forAll(List(transform(fun)))
                      
                
                // the following are the list of (implicit) parameters that need to be provided 
                // when calling Prop.forall
                
                var buf = new collection.mutable.ListBuffer[Tree]()
                      
                val blockValSym = newSyntheticValueParam(fun.symbol, definitions.BooleanClass.typeConstructor)
                      
                val fun2 = localTyper.typed {
                  val body = Prop.propBoolean(resetAttrs(Ident(blockValSym)))
                  Function(List(ValDef(blockValSym, EmptyTree)), body)
                }.asInstanceOf[Function]
                       
                   
                new ChangeOwnerTraverser(fun.symbol, fun2.symbol).traverse(fun2);
                new ForeachTreeTraverser({t: Tree => t setPos tree.pos}).traverse(fun2)
                      
                buf += Block(Nil,fun2)
              
              
                if(vtpes.size <= 1) {
                  buf += resetAttrs(Arbitrary.arbitrary(tpe))
                  buf += resetAttrs(Shrink.shrinker(tpe))
                } else {
                  for { tpe <- vtpes } {
                    buf += resetAttrs(Arbitrary.arbitrary(tpe))
                    buf += resetAttrs(Shrink.shrinker(tpe))
                  }
                }
                   

                import posAssigner.atPos         // for filling in tree positions

                   
                val property = localTyper.typed {
                  atPos(tree.pos) {
                    Apply(prop, buf.toList)
                  }
                }
                
                
                
                localTyper.typed {
                  atPos(tree.pos) {
                    Test.isPassed(Test.check(property))
                  }
                }
              
            
            case t => 
              assert(false, "expected ValDef of type TypeRef, found "+t)
              tree
          }
        }
  
  	    /** Delegates the recursive traversal of the tree. */
       	case _ => super.transform(tree)
      }
      
    }
    
     class ChangeOwnerTraverser(val oldowner: Symbol, val newowner: Symbol) extends Traverser {
    override def traverse(tree: Tree) {
	      if (tree != null && tree.symbol != null && tree.symbol != NoSymbol && tree.symbol.owner == oldowner)
	        tree.symbol.owner = newowner;
	      super.traverse(tree)
	    }
	  }
    
    /** Synthetic value parameters when parameter symbols are not available
    */
    final def newSyntheticValueParam(owner: Symbol, argtype: Type): Symbol = {
      var cnt = 0
      def freshName() = { cnt += 1; newTermName("x$" + cnt) }
      def param(tp: Type) =
    	  owner.newValueParameter(owner.pos, freshName()).setFlag(scala.tools.nsc.symtab.Flags.SYNTHETIC).setInfo(tp)
      param(argtype)
    }
    
    private def isSelectOfSpecsMethod(s: Symbol, method: String): Boolean = 
      s == specsModule.tpe.decl(method)
    
        
    
    /** Quick (and dirty) hack for enabling tree substitution for pair elements.
     * Specifically, this allow to map pair accesses such as p._1 to a new variable 'x'
     * ([p._1 |-> x, p._2 |-> y, ..., p._n |-> z])
     */
    class MyTreeSubstituter(from: List[Symbol], to: List[Tree]) extends TreeSubstituter(from,to) {
      override def transform(tree: Tree): Tree = tree match {
        // Useful for substutite values like p._1 where 'p' is a pair
        // Inherithed 'TreeSubstituter' cannot handle this
	    case Select(Ident(_), name) =>
          def subst(from: List[Symbol], to: List[Tree]): Tree =
            if (from.isEmpty) tree
            else if (tree.symbol == from.head) to.head
            else subst(from.tail, to.tail);
          subst(from, to)
	    case _ =>
          super.transform(tree)
      }
    }
    
  }
  
}
