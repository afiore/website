---
title: "Discarded effects: a common pitfall when working with Scala IO monads" 
author: Andrea Fiore
authorURL: http://twitter.com/afiore
---

One thing that I would be certainly missing if I was to drop Scala in favor of another programming language is a 
mechanism to suspend the execution of side effects. 

This programming technique is available in Scala thanks to the popular [Cats Effects](https://typelevel.org/cats-effect/) library
as well some other alternative implementations such as [Monix](https://monix.io/) or [ZIO](https://zio.dev/). 

## Why using an effect system

I find myself reaching out to Cats' IO monad for two main reasons:

- _It allows to clearly tell apart the bits of program logic that perform side effects from the ones that do not_ 
  (e.g. issuing an RPC call vs sorting the items of a list). I find this reassuring: I am aware that I cannot be 
  fully in control of the former, as they involve interactions with the world outside my program. However, I know that, 
  as long as I don't break [referential transparency](https://wiki.haskell.org/Referential_transparency), the latter 
  will be fully deterministic and therefore easier to manage and reason about.
  
- _IO provides a means to manipulate side effecting code using the same high order functions_ one gets accustomed to while 
  working with Scala collections methods  (e.g. map, flatMap, foldLeft, traverse, etc).
  
While embracing this programming style, it is important to be aware of a quirk of Scala known as _value discarding_. 
In this post, I will illustrate how this conversion rule, applied by default by the Scala compiler, might cause your code 
to behave unexpectedly. Such misbehaviour might equally affect your program as it runs in production as well as it 
gets tested in CI. I will point at an easy way to avoid this pitfall by tweaking the compiler flags, thus turning potentially nasty 
runtime bugs into compile time errors.

## The pitfall: discarded effects

![xkcd haskell comic](https://imgs.xkcd.com/comics/haskell.png )

Suppose we are working on some simple CURD functionality and you have refactored your persistence code as follows:
```scala
class DAO1
object DAO1 {
  def setup(xa: Transactor[IO]): IO[DAO1] = IO {
    println("running pretend DB migrations for DAO1 ...")
    new DAO1
  }
}
class DAO2
object DAO2 {
  def setup(xa: Transactor[IO]): IO[DAO2] = IO {
    println("running pretend DB migrations for DAO2 ...")
    new DAO2
  }
}

class DAO3(dao1: DAO1, dao2: DAO2)

def withDAO(dbAction: DAO3 => IO[Unit]): Unit =
  withTransactor { xa =>
    for {
      dao1 <- DAO1.setup(xa)
      dao2 <- DAO2.setup(xa)
    } yield dbAction(new DAO3(dao1, dao2))
  }.unsafeRunSync()

withDAO { dao => IO(println(s"using DAO: $dao")) }
// running pretend DB migrations for DAO1 ...
// running pretend DB migrations for DAO2 ...
```
Let's break this snippet apart:

- `withTransactor` is a function that establishes a database connection, 
   performs a supplied IO within a transaction, and then safely disposes of the connection. 
   It's signature is: 
```scala
   def withTransactor[T](f: Transactor[IO] => IO[T])
     : IO[T]
```
-  We pass in to `withTransactor` an effectfull function. This initialises two _data access objects_ by running their associated
   db migrations, instantiates DAO3, and finally applies to it the supplied `dbAction` function.

However, _there is something fishy in this code_! While all the `setup` calls in the for comprehension desugar 
into a sequence of `flatMap` calls, the final call to `runAction` which we yield is translated into a call to the `map` method. 

This results in an expression of type `IO[IO[Unit]]`, which in itself doesn't align with the return type stated in 
`withTransactor`'s signature, and yet compiles just fine.

Enter value discarding. As concisely stated in [section 6.26.1](https://scala-lang.org/files/archive/spec/2.12/06-expressions.html#value-conversions)
of the Scala language specification, value discarding is an _"implicit conversion"_ that can be applied to _"an expression 
which is type checked with some expected type"_.

> If e has some value type and the expected type is Unit, e is converted to the expected type by embedding it in the term { e; () }.

This rule was probably intended as an ergonomic touch to make the compiler a bit smarter and less pedantic. 
However, I would argue that here it is working against the programmer, as what gets discarded and never executed is 
likely to be some important part of our program logic.

## Compiler flags to the rescue

Despite it does not provide a proper way to disable this conversion rule all together, the Scala 2 compiler can be easily configured 
to be more strict with regard to value discarding. When used in combination with `-Xfatal-warning`, setting the `-Ywarn-value-discard`
flag will result in a compilation error. In the case of the snippet above, we would have encountered the following (fatal) warning:

```scala
[error] example.scala:12 discarded non-Unit value
```

As a reminder, this is the relevant Sbt setting to set these flags:

```scala
scalacOptions ++= Seq(
  "-Ywarn-value-discard",
  "-Xfatal-warnings"
)
```

## Discarded assertions

In fairness, one could argue that this pitfall could have been easily detected with some basic test coverage in place. 

A test asserting that whatever effect our IO performs has actually occurred would have spotted this. 
However, this is true only as long as our testing logic is actually executed and does not get discarded by the compiler. 

As an example, let's take the previous snippet and bring it the context of a unit test.

```scala
import org.scalatest.{Matchers, FlatSpec}

class DiscardingTest extends FlatSpec with Matchers {
  "DAO4" should "persist a record" in {
     withDAO4 { dao =>
       for {
         _ <- dao.persist(new Record)
         persisted <- dao.persisted
       } yield persisted.nonEmpty shouldBe true
     }
  }

  private def withDAO4(dbAction: DAO4 => IO[Unit]): Unit =
    withTransactor { xa =>
      for {
        dao1 <- DAO1.setup(xa)
        dao2 <- DAO2.setup(xa)
        records <- Ref[IO].of(List.empty[Record])
      } yield dbAction(new DAO4(dao1, dao2, records))
    }.unsafeRunSync()
}

//buggy DAO4 implementation
class DAO4(dao1: DAO1, dao2: DAO2, records: Ref[IO, List[Record]]) {
  def persist(record: Record): IO[Unit] = for {
    _ <- IO.raiseError( new IllegalStateException("This exception might be discarded!"))
    _ <- records.update(_ :+ record)
  } yield ()

  def persisted: IO[List[Record]] = records.get
}
```

`withDAO4` is a helper method intended to facilitate testing our persistence logic. This time, the IO function we pass in as 
argument will do the following: 
- `persist` a record.
- sequence a second IO call returning the list of persisted records.
- asserts that the returned value is not an empty collection.

But what happens when we actually run the test above?

```scala
import org.scalatest._

durations.stats.nocolor.run(new DiscardingTest)
// Run starting. Expected test count is: 1
// Session$App$DiscardingTest$1:
// DAO4
// running pretend DB migrations for DAO1 ...
// running pretend DB migrations for DAO2 ...
// - should persist a record (6 milliseconds)
// Run completed in 7 milliseconds.
// Total number of tests run: 1
// Suites: completed 1, aborted 0
// Tests: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
// All tests passed.
```

From bad to worse: not only our `dbAction` is discarded, but Scalatest happily reports that our test succeeded!

I have not spent time figuring out properly the mechanics whereby the library determines this outcome. From the outside, it seems like it is 
taking an optimistic approach and deems a test successful unless at least one of its assertions fail. 

## Conclusions: avoid a double facepalm!

I have become aware of value discarding a while back, through an [excellent post](https://underscore.io/blog/posts/2016/11/24/value-discard.html) 
by Richard Dallaway. A few years later, this arguably strange behaviour is still the default for both Scala2 and Dotty. 
Promisingly, a [pre-SIP](https://contributors.scala-lang.org/t/pre-sip-limiting-value-discards/3729) has been recently put forward 
to make the mechanism fully explicit, or configurable on a whitelist basis.
Hopefully this this proposal will get some traction and ultimately lead to a more sensible default for this feature.

Retrospectively, I can say that I have fell for this pitfall at least twice, and in both cases the discarded effect was 
in my test code was hiding an actual bug in the implementation (a true _double facepalm_ moment!).

![double facepalm meme](/img/posts/double-facepalm.jpm)

As a recommendation to fellow functional programmers picking up Cats effects and similar, I would definitely suggest 
spending time learning about the available [compiler flags](https://docs.scala-lang.org/overviews/compiler-options/index.html#Warning_Settings), 
and how they might be leveraged to make your build safer and sharper. Unless it is really not viable to do so,
making warnings fatal is 99% of the times a good idea, especially if you are working on a relatively small
and new code-base. 

The other, perhaps bit more obvious piece of wisdom I feel like sharing (also as a note to self), is to never
trust blindly a green Scalatest suite. It is always a good idea to satisfy yourself that your assertions fail when you explicitly
break your implementation. While I learn more on how to best [automate this process](https://en.wikipedia.org/wiki/Mutation_testing),
ensuring that this verification is carried out will be a matter of discipline and habit.

