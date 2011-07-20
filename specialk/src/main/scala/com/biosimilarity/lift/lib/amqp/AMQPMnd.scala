// -*- mode: Scala;-*- 
// Filename:    AMQPMnd.scala 
// Authors:     lgm                                                    
// Creation:    Tue Jul 12 01:50:41 2011 
// Copyright:   Not supplied 
// Description: 
// ------------------------------------------------------------------------

package com.biosimilarity.lift.lib

import com.biosimilarity.lift.lib.moniker._
import com.biosimilarity.lift.lib.monad._

import net.liftweb.amqp._

import scala.util.continuations._

import scala.concurrent.{Channel => Chan, _}
import scala.concurrent.cpsops._

import _root_.com.rabbitmq.client.{ Channel => RabbitChan, _}
import _root_.scala.actors.Actor

import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.io.json.JettisonMappedXmlDriver

import java.net.URI
import _root_.java.io.ObjectInputStream
import _root_.java.io.ObjectOutputStream
import _root_.java.io.ByteArrayInputStream
import _root_.java.io.ByteArrayOutputStream
import _root_.java.util.Timer
import _root_.java.util.TimerTask

trait AMQPBrokerScope[T] {
  self : MonadicDispatcherScope[T] =>
    def factory : ConnectionFactory

  override type MDS[A] = AMQPQueueM[A]
  override def protoMDS[A] =
    new AMQPQueueM[A](
      "", -1, "", ""
    )

  case class AMQPQueue[A](    
    exchange : String,
    routingKey : String,
    dispatcher : theMDS.Generator[A,Unit,Unit],
    sender : theMDS.Generator[Unit,A,Unit]
  ) {
    def !( msg : A ) : Unit = {
      reset { for( _ <- sender ) { msg } }
    }
  }

  class AMQPQueueM[A](
    override val host : String,
    override val port : Int,
    val exchange : String,
    val routingKey : String
  ) extends StdMonadicAMQPDispatcher[A]( host, port )
  with ForNotationShiv[AMQPQueue,A] 
  with ForNotationApplyShiv[AMQPQueue,A]
  with BMonad[AMQPQueue]
  with MonadPlus[AMQPQueue]
  with MonadFilter[AMQPQueue] {
    override type ForNotationTrampoline[A] = QCell[A]
    case class QCell[A](
      queue : AMQPQueue[A]
    ) extends SCell( queue ) {
      override def foreach ( f : A => Unit ) : Unit = {
	reset { for( msg <- queue.dispatcher ) { f( msg ) } } ;
	()
      }
    }    

    def sender [A] ( 
      exchange : String,
      routingKey : String
    ) : theMDS.Generator[Unit, A, Unit] = {
      theMDS.Generator {
	k : ( Unit => A @suspendable ) => {
	  for( channel <- senderChannel( exchange, routingKey ) ) {
	    spawn {
	      val qname = ( exchange + "_queue" )
		channel.exchangeDeclare( exchange, "direct" )
	      channel.queueDeclare( qname, true, false, false, null );
	      channel.queueBind( qname, exchange, routingKey )
	      
	      val bytes = new ByteArrayOutputStream
	      val store = new ObjectOutputStream(bytes)
	      store.writeObject( k() )
	      store.close
	      channel.basicPublish(
		exchange,
		routingKey,
		null,
		bytes.toByteArray
	      )	    
	    }
	  }
	}
      }
    }
    
    def senderChannel [A] ( 
      exchange : String,
      routingKey : String
    ) : theMDS.Generator[Channel, Unit, Unit] = {
      theMDS.Generator {
	k : ( Channel => Unit @suspendable ) => {
	  val conn = factory.newConnection( Array { new Address(host, port) } )
	  val channel = conn.createChannel()
	  k( channel )
	}
      }
    }

    override def apply [S] (
      queue : AMQPQueue[S]
    ) : ForNotationTrampoline[S] = {
      QCell[S]( queue )
    }

    override def unit [S] ( s : S ) : AMQPQueue[S] = {
      val rslt = zero[S]
      spawn {
	reset { for( e <- rslt.sender ) { s } }
      }
      rslt
    }
    override def bind [S,T] (
      amqpS : AMQPQueue[S],
      f : S => AMQPQueue[T]
    ) : AMQPQueue[T] = {
      val acc = zero[T]
      spawn {
	reset {
	  for( s <- amqpS.dispatcher ) {
	    val amqpT = f( s )
	    spawn {
	      reset {
		for( t <- amqpT.dispatcher ) {
		  for( e <- acc.sender ) { t }
		}
	      }
	    }		
	  }
	}
      }
      acc
    }
    override def zero [A] : AMQPQueue[A] = {
      AMQPQueue[A](
	exchange,
	routingKey,
	theMDS.serve[A]( factory, host, port, exchange ),
	sender( exchange, routingKey )
      )
    }
    override def plus [A] (
      amqpA1 : AMQPQueue[A],
      amqpA2 : AMQPQueue[A]
    ) : AMQPQueue[A] = {
      val amqpA12 = zero[A]
      spawn {
	reset {
	  for( a1 <- amqpA1.dispatcher ) {
	    for( e <- amqpA12.sender ) { a1 }
	  }
	}
      }
      spawn {
	reset {
	  for( a2 <- amqpA2.dispatcher ) {
	    for( e <- amqpA12.sender ) { a2 }
	  }
	}
      }
      amqpA12
    }
    override def mfilter [S] (
      amqpS : AMQPQueue[S],
      pred : S => Boolean
    ) : AMQPQueue[S] = {
      val rslt = zero[S]
      spawn {
	reset {
	  for( s <- amqpS.dispatcher ) {
	    if ( pred( s ) ) {
	      for( e <- rslt.sender ) { s }
	    }
	  }
	}
      }
      rslt
    }

    override def equals( o : Any ) : Boolean = {
      o match {
	case that : AMQPQueueM[A] => {
	  (
	    host.equals( that.host )
	    && port.equals( that.port )
	    && exchange.equals( that.exchange )
	    && routingKey.equals( that.routingKey )
	  )
	}
	case _ => false
      }
    }
    
    override def hashCode( ) : Int = {
      (
	( 37 * host.hashCode )
	+ ( 37 * port.hashCode )
	+ ( 37 * exchange.hashCode )
	+ ( 37 * routingKey.hashCode )
      )
    }
  }

  object AMQPQueueM {
    def apply [A] (
      host : String,
      port : Int,
      exchange : String,
      routingKey : String
    ) : AMQPQueueM[A] = {
      new AMQPQueueM[A] ( host, port, exchange, routingKey )
    }
    def unapply [A] (
      amqpQM : AMQPQueueM[A]
    ) : Option[( String, Int, String, String )] = {
      Some(
	( amqpQM.host, amqpQM.port, amqpQM.exchange, amqpQM.routingKey )
      )
    }
  }

  case class AMQPQueueHostExchangeM[A] (
    override val host : String,
    override val exchange : String
  ) extends AMQPQueueM[A](
    host,
    AMQPDefaults.defaultPort,
    exchange,
    "routeroute"
  )

  case class AMQPQueueHostURIM[A] (
    uri : URI
  ) extends AMQPQueueM[A](
    uri.getHost,
    uri.getPort,
    uri.getPath.split( "/" )( 1 ),
    uri.getPath.split( "/" )( 0 )
  )

  case class AMQPQueueHostMonikerM[A] (
    moniker : Moniker
  ) extends AMQPQueueM[A](
    moniker.getHost,
    moniker.getPort,
    moniker.getPath.split( "/" )( 1 ),
    moniker.getPath.split( "/" )( 0 )
  )

  def apply [A] (
    m : AMQPQueueM[A],
    queue : AMQPQueue[A]
  ) : theMDS.Generator[A,Unit,Unit] = {
    theMDS.Generator {
      k : ( A => Unit @suspendable ) => {	
	for( msg <- queue.dispatcher ) {
	  import m._
	  k( msg )
	}
      }
    }
  }
}

class AMQPScope [T] (
  override val factory : ConnectionFactory
) extends AMQPBrokerScope[T]
with MonadicDispatcherScope[T] {
  override def equals( o : Any ) : Boolean = {
    o match {
      case that : AMQPScope[T] => {
	factory.equals( that.factory )
      }
      case _ => false
    }
  }
  override def hashCode( ) : Int = {
    37 * factory.hashCode
  }
}

object AMQPScope {
  def apply [T] (
    factory : ConnectionFactory
  ) : AMQPScope[T] = {
    new AMQPScope[T] ( factory )
  }
  def unapply [T] (
    amqpScope : AMQPScope[T]
  ) : Option[( ConnectionFactory )] = {
    Some( ( amqpScope.factory ) )
  }
}

case class AMQPStdScope[T] (  
) extends AMQPScope[T] (
  AMQPDefaults.defaultConnectionFactory
) 

package usage {
  import scala.collection.mutable.HashMap
  import java.util.UUID  

  object AMQPSample extends UUIDOps {
    val defaultSrcHost = "10.0.1.5"
    val defaultTrgtHost = "10.0.1.9"

    val defaultQueueUUID = getUUID
    
    val srcSeed = scala.math.round( scala.math.random * 100 ).toInt
    val trgtSeed = scala.math.round( scala.math.random * 100 ).toInt
        
    def setupAndRunTest(
      parity : Boolean,
      srcHost : String,
      trgtHost : String,
      queueStr : String,
      msgCount : Int
    ) = {
      val srcScope = new AMQPStdScope[Int]()
      val trgtScope = new AMQPStdScope[Int]()
      
      val srcQM =
	new srcScope.AMQPQueueHostExchangeM[Int]( srcHost, queueStr )
      val trgtQM =
	new trgtScope.AMQPQueueHostExchangeM[Int]( trgtHost, queueStr )

      val srcQ = srcQM.zero[Int]
      val trgtQ = trgtQM.zero[Int]

      val msgMap = new HashMap[Int,Int]()

      def loop( count : Int ) : Unit = {
	println( "entering msg loop with count : " + count )
	count match {
	  case 0 => {
	    println( "Nothing to do." )
	  }
	  case i => {
	    if ( i < 0 ) {
	      throw new Exception(
		"Tsk, tsk, play fair, now... please keep msg counts positive!"
	      )
	    }
	    else {
	      println(
		"Waiting for a message on queue : " + queueStr
	      )
	      for( msg <- trgtQM( trgtQ ) ) {
		val mms = msgMap.size
		println(
		  (
		    "received msg number " + mms
		    + " with contents " + msg
		    + " on " + queueStr 
		  )
		)

		msgMap += ( ( (mms + 1), msg ) )

		if ( mms == count - 1 ) {
		  if ( !parity ) {
		    println(
		      "Ha! We have the last word with msg " + mms
		    )
		    srcQ ! mms
		  }
		  println( "All " + count + " messages sent and received." )
		  println( "Conversation summary: " )
		  for( order <- 1 to count ) {
		    val msg = msgMap( order )
		    val prefix = "received " + msg + " "
		    val suffix = 
		      order match {
			case 1 => 1 + "st" + " "
			case 2 => 2 + "nd" + " "
			case 3 => 3 + "rd" + " "
			case _ => order + "th" + " "
		      }
		    println( prefix + suffix + "msg" )
		  }
		  println( "Test successful." )
		}
		else {
		  if ( mms < count ) {
		    println( "replying with msg " + mms )
		    srcQ ! mms
		  }
		  else {
		    println( "received unexpected msg: " + msg )
		  }
		}
	      }
	    }
	  }
	}
      }
      
      if ( parity ) {
	println(
	  "sending initial msg " + srcSeed + " on queue " + queueStr
	)
	srcQ ! srcSeed
      }

      loop( msgCount )
    }
    
  }

/*
Welcome to Scala version 2.9.0.1 (Java HotSpot(TM) 64-Bit Server VM, Java 1.6.0_26).
Type in expressions to have them evaluated.
Type :help for more information.

scala> import com.biosimilarity.lift.lib._
import com.biosimilarity.lift.lib._
import com.biosimilarity.lift.lib._

scala> import com.biosimilarity.lift.lib.usage._
import com.biosimilarity.lift.lib.usage._
import com.biosimilarity.lift.lib.usage._

scala> import scala.util.continuations._
import scala.util.continuations._
import scala.util.continuations._

scala> import AMQPSample._
import AMQPSample._
import AMQPSample._

scala> setupAndRunTest( true, defaultSrcHost, defaultTrgtHost, "queueRS", 10 )
setupAndRunTest( true, defaultSrcHost, defaultTrgtHost, "queueRS", 10 )
sending initial msg 47 on queue queueRS
entering msg loop with count : 10
Waiting for a message on queue : queueRS

scala> received msg number 0 with contents 0 on queueRS
replying with msg 0
received msg number 1 with contents 1 on queueRS
replying with msg 1
received msg number 2 with contents 2 on queueRS
replying with msg 2
received msg number 3 with contents 3 on queueRS
replying with msg 3
received msg number 4 with contents 4 on queueRS
replying with msg 4
received msg number 5 with contents 5 on queueRS
replying with msg 5
received msg number 6 with contents 6 on queueRS
replying with msg 6
received msg number 7 with contents 7 on queueRS
replying with msg 7
received msg number 8 with contents 8 on queueRS
replying with msg 8
received msg number 9 with contents 9 on queueRS
All 10 messages sent and received.
Conversation summary: 
received 0 1st msg
received 1 2nd msg
received 2 3rd msg
received 3 4th msg
received 4 5th msg
received 5 6th msg
received 6 7th msg
received 7 8th msg
received 8 9th msg
received 9 10th msg
Test successful.
:q
:q
*/
}
