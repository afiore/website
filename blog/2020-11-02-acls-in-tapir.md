---
title: "Type driven API development using Scala and Tapir"
author: Andrea Fiore
authorURL: http://twitter.com/afiore
---

In a previous post, I have discussed at length _why_, in order to grow a mature API driven product, we need a mechanism
to keep API documentation and implementations in sync. It’s now time for me to illustrate _how_ such 
a mechanism might look like in the backend; so let's get our hands dirty and write some code!

In this post, I will use [Tapir](https://tapir-scala.readthedocs.io/) - an excellent open source library by [Softwaremill](https://softwaremill.com/) - to demonstrate 
a _[code first](https://swagger.io/blog/api-design/design-first-or-code-first-api-development/)_ approach to API development in Scala.

The plan is to work our way through building a couple of REST endpoints for managing Kafka ACLs.
For the sake of simplicity, we will only be creating and listing ACL rules; which is only a subset of all the operations we 
would need for a complete API. Also, we will deliberately gloss over the actual persistence of the ACL rules into an actual datastore 
(e.g. Zookeeper or similar), and we will simply store them in-memory. Similarly, I will briefly cover how Tapir can handle authentication 
and authorisation, but for simplicity I will leave this unimplemented in most of my code samples.

### Modelling our API entities

Kafka ACLs (Access control lists) are a built-in authorisation mechanism whereby administrators can control access to a cluster’s data. 
In a nutshell, a Kafka _ACL binding_ comprises of the following key attributes:

- A _resource_ on which to perform some sort of operation
- The _operation_ itself (which varies, depending on the resource)
- A _principal_ (i.e. the entity to be authorised)
- A permission type (i.e. can be either `Allow` or `Deny`)

In Scala, we would model acls as follows:

```scala mdoc:invisible
import enumeratum._

sealed trait ResourceType extends EnumEntry

object ResourceType extends Enum[ResourceType] with CirceEnum[ResourceType]{
    case object CLUSTER extends ResourceType
    case object DELEGATION_TOKEN extends ResourceType
    case object GROUP extends ResourceType
    case object TOPIC extends ResourceType
    case object TRANSACTION_ID extends ResourceType
    case object UNKNOWN extends ResourceType

    override val values = findValues
}

sealed trait PatternType extends EnumEntry
object PatternType extends Enum[PatternType] with CirceEnum[PatternType]{
    case object LITERAL extends PatternType
    case object MATCH extends PatternType
    case object PREFIXED extends PatternType
    case object UNKNOWN extends PatternType

    override val values = findValues
    
}

sealed trait Operation extends EnumEntry
object Operation extends Enum[Operation] with CirceEnum[Operation] {
    case object ALL extends Operation
    case object ALTER extends Operation
    case object ALTER_CONFIGS extends Operation
    case object CLUSTER_ACTION extends Operation
    case object CREATE extends Operation
    case object DELETE extends Operation
    case object DESCRIBE extends Operation
    case object DESCRIBE_CONFIGS extends Operation
    case object IDEMPOTENT_WRITE extends Operation
    case object READ extends Operation
    case object UNKNOWN extends Operation
    case object WRITE extends Operation

    override val values = findValues
}

sealed trait PermissionType extends EnumEntry
object PermissionType extends Enum[PermissionType] with CirceEnum[PermissionType] {
    case object ALLOW extends PermissionType
    case object DENY extends PermissionType
    override val values = findValues
}

/// TODO: describe these types in the post
type AuthToken = String

final case class User(username: String)

import io.circe.generic.semiauto.deriveCodec
import io.circe.Codec

final case class ApiError(error: String)
object ApiError {
  implicit val codec: Codec[ApiError] = deriveCodec
}
```
```scala mdoc:nest

import io.circe.generic.semiauto.deriveCodec
import io.circe.Codec

final case class ResourcePattern(resourceType: ResourceType,
                                 resource: String,
                                 patternType: PatternType)
object ResourcePattern {
  implicit val codec: Codec[ResourcePattern] = deriveCodec
}

final case class AclEntry(principal: String,
                          host: String,
                          operation: Operation,
                          permissionType: PermissionType)

object AclEntry {
  implicit val codec: Codec[AclEntry] = deriveCodec
}

final case class AclBinding(pattern: ResourcePattern, entry: AclEntry)
object AclBinding {
  implicit val codec: Codec[AclBinding] = deriveCodec
}
```

Here we define an immutable record type called `AclBinding`; in Scala parlance, a case class. This wraps a resource 
`pattern` and an `entity`, which combined represent an ACL authorisation rule (please refer to the [Kafka 2.5 Javadoc](https://kafka.apache.org/25/javadoc/) 
for the possible values of enumerables such us `ResourceType`, `PatternType`, or `Operation`). This is the only entity our API will revolve around, and here is how we can define some sample values:

```scala mdoc:nest

object Examples {
  val validAcl1 = AclBinding(ResourcePattern(ResourceType.TOPIC, "position-report", PatternType.LITERAL), AclEntry("User:andrea", "example.cloud", Operation.ALL, PermissionType.ALLOW))

  val validAcl2 = AclBinding(ResourcePattern(ResourceType.CLUSTER, "kafka-test-", PatternType.PREFIXED), AclEntry("Group:testers", "example.cloud1", Operation.DESCRIBE, PermissionType.ALLOW))

  val invalidAcl = AclBinding(ResourcePattern(ResourceType.TRANSACTION_ID, "*", PatternType.LITERAL), AclEntry("User:andrea", "cloud1", Operation.ALTER, PermissionType.ALLOW))

}
```

Now, let's move on to defining a REST endpoint to create a Kafka ACL!

### Strongly typed endpoint definitions

In Tapir, REST endpoints are described as values of type `Endpoint[I, E, O, S]` where:

- `I` is a _tuple_ representing the various endpoint inputs (e.g. dynamic path fragments, query params, as well as its parsed request payload).
- `E` and `O` are output types for the error (e.g. 400 Bad Request) and the success (2xx) case.
- `S` is the type of streams that are used by the endpoint’s inputs/outputs. This is relevant only for more advanced use 
  cases such as defining _Server Sent Events_ and Websocket endpoints, which we will not be covering in this post.

In order to declare such complex type definitions, the library provides us with a builder syntax that allows us to incrementally declare our endpoint's 
inlets and outlets bit by bit, with a high degree of precision:

```scala mdoc:nest

import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.model.StatusCode

object AclEndpoints {
    val createNewAcl: Endpoint[(AuthToken, AclBinding), ApiError, Unit, Nothing] = endpoint
      .post
      .in("api" / "kafka" / "acls")
      .in(header[AuthToken]("x-api-token"))
      .in(jsonBody[AclBinding])
      .errorOut(jsonBody[ApiError])
      .out(statusCode(StatusCode.Created))
}
```

There's quite a lot going on here already, so let's start unpacking it bit by bit:

We start building the endpoint using the constructor value `endpoint` of type `Enpoint[Unit, Unit, Unit, Nothing]`.
This acts as the entry point into the Tapir DSL. Using such syntax, we also specify that:

- Our endpoint's uses the http method `POST` 
- it will be bound to the path `/api/kafka/acls`
- it will expect a header called `x-api-token` as well as a JSON request body (parsable as `AclBinding`) as its input
- On success, it will respond with a status code of `201 Created` and an empty response payload.
- On error, it will respond with the JSON representation of an `ApiError`.

With our first endpoint defined in all its nitty gritty details, it's now time move on and implement the underlying logic.
But first, let's quickly flash out another endpoint to list the persisted Acls:

```scala mdoc:nest

import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.codec.enumeratum._
import sttp.model.StatusCode

object AclEndpoints {
    val createNewAcl = endpoint
      .post
      .in("api" / "kafka" / "acls")
      .in(header[AuthToken]("x-api-token"))
      .in(jsonBody[AclBinding].description("The json representation of an ACL binding").example(Examples.validAcl1))
      .errorOut(jsonBody[ApiError])
      .out(statusCode(StatusCode.Created))


    val listAllAcls = endpoint
      .get
      .in("api" / "kafka" / "acls")
      .in(header[AuthToken]("x-api-token"))
      .in(query[Option[ResourceType]]("resourceType")
           .description("An optional ResourceType value to filter by")
           .example(Some(ResourceType.TOPIC)))
      .out(jsonBody[List[AclBinding]])
}
```

### Wiring up our business logic

As mentioned before, we plan on stubbing the persistence of our ACL bindings into a in-memory structure.
However, in order to do so we will still need to rely on a real HTTP server capable of handling incoming client 
requests.

For this post, I have chosen to use [Http4s](https://http4s.org/), a library that allows to work with HTTP in a _purely functional_ style.
Please do not run away if this is not your library of choice. As well as for Http4s, Tapir provides support for several other 
Scala HTTP server implementations such us Akka-HTTP, Play, Finatra, and Vert.X.

```scala mdoc:invisible
object AclValidation {
  import ResourceType._
  import Operation._

  private val clusterOps = Set[Operation](ALTER, ALTER_CONFIGS, CLUSTER_ACTION, CREATE, DESCRIBE, DESCRIBE_CONFIGS, IDEMPOTENT_WRITE)

  private val topicOps = Set[Operation](ALTER, ALTER_CONFIGS, CREATE, DELETE, DESCRIBE, DESCRIBE_CONFIGS, READ, WRITE)

  private val groupOps = Set[Operation](DELETE, DESCRIBE, READ)

  private val tokenOps = Set[Operation](DESCRIBE)

  private val transactionOps = Set[Operation](DESCRIBE, WRITE)

  val Error = ApiError("The supplied ACL binding is invalid")

  def isValid(acl: AclBinding): Boolean =
      (acl.pattern.resourceType, acl.entry.operation) match {
      case (_, ALL) => true
      case (CLUSTER, op) => clusterOps(op)
      case (TOPIC, op) => topicOps(op)
      case (GROUP, op) => groupOps(op) 
      case (DELEGATION_TOKEN, op) => tokenOps(op)
      case (TRANSACTION_ID, op) => transactionOps(op)
      case _ => false
    }
}
```
```scala mdoc:nest
import sttp.tapir._
import sttp.tapir.server.http4s._
import cats.effect.IO
import org.http4s.HttpRoutes
import cats.effect.{ContextShift, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._

class AclRoutes(aclStore: Ref[IO, Set[AclBinding]])(implicit cs: ContextShift[IO], timer: Timer[IO]) {
  private val createNewAcl: HttpRoutes[IO] =
    AclEndpoints.createNewAcl.toRoutes { case (_, aclBinding) => 
       if (!AclValidation.isValid(aclBinding)) IO.pure(Left(AclValidation.Error))
       else aclStore.update(_ + aclBinding).map(_ => Right(()))
    }

  private val listAcls: HttpRoutes[IO] =
    AclEndpoints.listAllAcls.toRoutes { case (_, maybeResourceType) => 
      val matchesResourceType: AclBinding => Boolean = acl => maybeResourceType.fold(true)(acl.pattern.resourceType == _)
      aclStore.get.map(_.toList.filter(matchesResourceType).asRight[Unit])
    }

  val combined: HttpRoutes[IO] = createNewAcl <+> listAcls
}
```

To start with, notice the `sttp.tapir.server.http4s._` import. This brings in a bunch of implicit 
classes that extend our `Endpoint[_,...]` with a `toRoutes` method. `toRoutes` _interprets_ the Tapir 
endpoint description into a `org.http4.HttpRoutes[IO]` (i.e. the actual HTTP server implementation). 
Also, notice how the input, error and output types of the two routes are fully aligned with the ones of our endpoint 
definitions. It is by this aliment mechanism that the library provides us with strong compile-time guarantees that 
our implementation won't drift away from the generated docs, and that our system _will do exactly what it says on the tin_.

Let's now look at the two route implementations. In `createNewAcl`, we pass the parsed payload `aclBinding` as an input for `AclValidation.isValid`. 
For simplicity, I am omitting the actual implementation of the `AclValidation` object. For the sake of example, let's say that `isValid` performs some simple logic to verify 
that the supplied combination of our Acl's `ResourceType` and `Operation` is valid as per the [reference table](https://docs.confluent.io/platform/current/kafka/authorization.html#operations) 
in the Confluent docs. If the validation succeeds and a `Right(())` is returned, we 
simply update our in memory store by adding the new acl binding. If the validation fails, we instead return 
a `Left` wrapping an `ApiError` value. 

The implementation of `listAcls` is equally simple. Here we read from our store, apply an optional `ResourceType` filter, and return the resulting set of acls as a list.
Unlike with the `createNewAcl`, we don't expect this endpoint to ever return a `Bad Request`, so we type its error as `Unit`.

Aside from reading and writing to the atomic reference `aclStore`, our ACL handling code here is pretty much pure and side-effect free. However, 
Tapir models the logic used to interpret the endpoint into an actual route as a function of the following shape: `I => IO[Either[E, O]`, or more generically `I => F[Either[E, O]`. 
This makes sense, as most read world API endpoints perform some sort of effectful computation such us opening sockets, interacting with a datastore, or reading/writing to disk.

### Authentication and other common route logic 

While in the endpoint definitions above we do specify an `x-api-token` header, you might have noticed that we haven't yet 
implemented any logic around this mandatory input. As it currently stands, our server logic is in fact completely insecure, and we should probably do something about it!

One simple way to approach this would be to implement an authentication helper function like the following and reuse it across all the endpoints we want to secure: 

```scala
def userOrApiError[A](token: ApiToken)(logic: User => IO[Either[]]): IO[Either[ApiError, A]]
```

For instance, we would extend the `createNewAcl` route as follows:

```scala
AclEndpoints.createNewAcl.toRoutes { case (apiToken, aclBinding) => 
   userOrApiError(apiToken) { user =>
     if (!AclValidation.isValid(aclBinding)) IO.pure(Left(AclValidation.Error))
     else aclStore.update(_ + aclBinding).map(_ => Right(()))
   }
}
```

This might look okay in a small code-base like ours, but it will probably not fly on a large 
one, as the boilerplate and the nesting of helper functions like `userOrApiError` will increase as 
our cross-cutting concerns become more complex and involved. 

Luckily for us, the authors of Tapir have recently come up with a nicer pattern to handle common logic such as 
authentication and authorisation. This revolves around the notion of partially defined endpoints which can combine 
an input/output description with some server logic:

```scala mdoc:nest

object SecureAclEndpoints {
  private def auth(token: AuthToken): IO[Either[ApiError, User]] = IO.pure {
    if (token == "let-me-in!") Right(User("legit"))
    else Left(ApiError("Forbidden"))
  }
  
  val secureEndpoint = endpoint
    .in(header[AuthToken]("x-auth-token"))
    .errorOut(jsonBody[ApiError])
    .serverLogicForCurrent(auth)
}
```

Both the endpoint definition and the server logic in `secureEndpoint` can now be neatly composed into other
 definitions:

```scala mdoc:nest
     val createNewAcl = SecureAclEndpoints
      .secureEndpoint
      .post
      .in("api" / "kafka" / "acls")
      .in(jsonBody[AclBinding])
      .out(statusCode(StatusCode.Created))
```

For more details on partial endpoints and other ways in which Tapir allows to abstract common logic, please refer to the
the [Server Logic](https://tapir.softwaremill.com/en/v0.16.16/server/logic.html) section of the official docs.

### Hitting our endpoints

Okay, so we have a couple of endpoints defined and implemented. Now we should probably check that they work as expected.
One way to do so without having to bind an actual web server to a port is to use Http4s DSL and hit our routes programmatically, as we would do in a simple unit test covering only the route logic.

```scala mdoc:nest:silent
import cats.effect.IO
import cats.effect.concurrent.Ref
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.headers._
import org.http4s._
import cats.effect.{ContextShift, Timer}
import org.http4s.circe._
import org.http4s.implicits._

implicit val cs: ContextShift[IO] =
  IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val t: Timer[IO] =
  IO.timer(scala.concurrent.ExecutionContext.global)  

implicit val aclEntityEncoder = jsonEncoderOf[IO, AclBinding]
implicit val aclEntityDecoder = jsonOf[IO, List[AclBinding]]
implicit val apiEntityDecoder = jsonOf[IO, ApiError]

val apiToken = Header("x-api-token", "let-me-in!")
val endpointUrl = Uri.unsafeFromString("/api/kafka/acls")

val store = Ref.unsafe[IO, Set[AclBinding]](Set.empty)
val routes = new AclRoutes(store).combined.orNotFound
```

Here we just setup the boilerplate needed to run some HTTP request through our web service:
We initialise a store and the `AclRoutes`, and then we compose the two routes
above into a single http service which will fallback to a 404 response should it fail to match 
the incoming request. With some help from the http4s DSL, we can now fire a few requests 
at our endpoints!

```scala mdoc:nest

//create a valid ACL binding
routes(
  Request[IO](method = Method.POST, endpointUrl)
    .withHeaders(apiToken)
    .withEntity(Examples.validAcl1)
).map(_.status).unsafeRunSync()

//try to submit an invalid one
routes(
  Request[IO](method = Method.POST, endpointUrl)
    .withHeaders(apiToken)
    .withEntity(Examples.invalidAcl)
).flatMap(resp => resp.as[ApiError].map(_ -> resp.status)).unsafeRunSync()

//Get the acl list 
routes(
  Request[IO](method = Method.GET, endpointUrl.withQueryParam("resourceType", ResourceType.TOPIC.entryName))
    .withHeaders(apiToken)
).flatMap(_.as[List[AclBinding]]).unsafeRunSync()
```

Hurray! Our endpoints seem to work as expected.

### Interpreting into an OpenAPI spec

With both endpoint declaration, implementation and testing covered, we are finally ready to look into
how Tapir helps us writing and maintaining high quality API docs. This is surprisingly straightforward
as it only involves grouping our endpoint definitions into a sequence and use a simple DSL to build an OpenAPI
spec: a machine readable specification detailing all the relevant attributes of our endpoints, from the query parameters
to the JSON schema of the request/response payloads.

```scala mdoc:nest
import sttp.tapir._
import sttp.tapir.openapi.OpenAPI
import sttp.tapir.docs.openapi._

val openAPISpec = List(
  AclEndpoints.createNewAcl,
  AclEndpoints.listAllAcls
).toOpenAPI("REST Kafka ACLs", "1.0")
```

Notice that the value returned by `toOpenAPI` is a syntax tree modelling an OpenAPI spec. Once computed,
this syntax tree can be modified and extended using plain Scala functions. Most of the times, this is something you will
not need doing, but it can provide a good escape hatch should you need to produce OpenAPI specs in a way that for some reason
Tapir doesn't support.

As a final step, you will probably want to serialise the spec into YAML so that it can be exported or served to the browser 
as an HTTP response:

```scala mdoc:nest
import sttp.tapir.openapi.circe.yaml._

println(openAPISpec.toYaml)
```

As a format, OpenAPI is agnostic of its presentation. However, several web-based UI tools exist to browse and interact with 
OpenAPI specs. This is how our endpoints look like when viewed in [SwaggerUI](https://editor.swagger.io/), one of the most popular OpenAPI viewer:

![endpoints see SwaggerUI](https://ibin.co/w800/5jSShLatLQ7N.png)

### Interpreting into an API client

Automatically generating API docs from our endpoint definitions is great, but it doesn't have to end there; we can be more ambitious and automate more aggressively!
As well as an OpenAPI spec, Tapir can also _interpret_ an endpoint definition into a fully functioning API client:

```scala mdoc:nest

import sttp.tapir.client.sttp._
import sttp.client3._
import sttp.model.Uri

class AclClient(apiBaseUrl: Uri, apiToken: String) {
   private val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

   def createNewAcl(aclBinding: AclBinding): IO[Either[ApiError, Unit]] = {
     val endpointInput = (apiToken, aclBinding)
     val request = AclEndpoints.createNewAcl.toSttpRequestUnsafe(apiBaseUrl).apply(endpointInput)
     IO(request.send(backend).body)
   }
}

```
The snippet above illustrates how to use Tapir to generate HTTP requests for the [Sttp](https://tapir.softwaremill.com/en/latest/client/sttp.html) client. The `toSttpRequestUnsafe` 
function brought in by the `sttp.tapir.client.sttp` import, takes in two parameters: 

- A `baseUrl` for our API server
- The endpoint inputs, as specified in the above definition (in this example, a `Touple2` containing the api key and the supplied ACL binding). 

Compared to our previous snippet, where we hit our endpoints using the Http4s DSL, this approach has some significant advantages: 
the generated Tapir API client neatly abstracts away the details of the HTTP implementation as well as the serialisation format, exposing only a function that maps our API inputs to its outputs. 

Arguably, working at this level of abstraction is for most engineers preferable than having to be
bogged down into the details of hand-wiring HTTP requests. Moreover, it is also safer, as it rules out a
whole class of trivial and yet very frequent programming errors (e.g. misspelling the API key header, omitting part of the 
ACL JSON payload, etc) while reducing the likelihood for the client implementation to go out of sync with the server.

### Conclusions

In this post, I have tried to demonstrate Tapir's main features by working through the implementation of a REST API 
for creating and listing Kafka ACLs. We saw how endpoint definitions, expressed as Scala types, can drive the 
implementation of both server side logic while at the same time automatically generate up-to-date
API docs, as well as fully functioning API clients.

Now, before you set off to introduce Tapir in your production code-base, please let me also share a few words of warning:

Firstly, despite its increasingly rich set of features and integrations, keep in mind that Tapir is still a relatively 
young project with only a couple of years of active development under his belt. While it is definitely reaching maturity,
I would still expect its API to occasionally introduce some breaking changes, which might make it harder to retrofit 
into a large existing project.

Secondly, like with every software framework, do keep in mind that all the good automation and safety that Tapir brings 
about comes at a cost. You will have to face a slightly higher degree of indirection, as the library centralises 
control over settings and behaviours that you would otherwise be able to control on a single route/endpoint basis 
(e.g. handling of unparsable input payloads, handling of exceptions, etc).

Also, be prepared to dive into some deep Scala rabbit holes, as Tapir leverages advanced features of the language 
such as type-level programming, type-class derivation, macros, etc. In other words, this is something you probably want to
stay clear from if you are still familiarising with the language.

That said, if you are not put off by either of the above, this might be a price worth paying in exchange for a higher 
degree of API integration, automation and consistency. I hope I have shared with you some of my enthusiasm for this excellent 
library, as I genuinely believe it makes building complex, API driven systems at scale easier and safer to 
a remarkable extent. 