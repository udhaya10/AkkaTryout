//#websocket-example-using-core

import akka.Done;
import akka.NotUsed;
import akka.actor.*;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebSocket;
import akka.http.javadsl.model.ws.WebSocketRequest;
import akka.http.javadsl.settings.ClientConnectionSettings;
import akka.http.javadsl.settings.ServerSettings;
import akka.http.javadsl.settings.WebSocketSettings;
import akka.japi.Function;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings({"Convert2MethodRef", "ConstantConditions"})
public class WebSocketCoreExample {

    public static Source<Message, ActorRef> source = Source.actorRef(100, OverflowStrategy.dropHead());
    public static ActorRef actorRef = null;
    public static ActorSystem system = null;

    public static ActorRef actor;
    public static ActorRef destinationRefParent;
    public static ActorRef destinationRefParentStreams;


    {
        ActorSystem system = null;
        ActorMaterializer materializer = null;
        Flow<HttpRequest, HttpResponse, NotUsed> handler = null;
        //#websocket-ping-payload-server
        ServerSettings defaultSettings = ServerSettings.create(system);

        AtomicInteger pingCounter = new AtomicInteger();

        WebSocketSettings customWebsocketSettings = defaultSettings.getWebsocketSettings()
                .withPeriodicKeepAliveData(() ->
                        ByteString.fromString(String.format("debug-%d", pingCounter.incrementAndGet()))
                );


        ServerSettings customServerSettings = defaultSettings.withWebsocketSettings(customWebsocketSettings);

        Http http = Http.get(system);
        http.bindAndHandle(handler,
                ConnectHttp.toHost("localhost", 8080),
                customServerSettings, // pass the configuration
                system.log(),
                materializer);
        //#websocket-ping-payload-server
    }

    {
        ActorSystem system = null;
        ActorMaterializer materializer = null;
        Flow<Message, Message, NotUsed> clientFlow = null;
        //#websocket-client-ping-payload
        ClientConnectionSettings defaultSettings = ClientConnectionSettings.create(system);

        AtomicInteger pingCounter = new AtomicInteger();

        WebSocketSettings customWebsocketSettings = defaultSettings.getWebsocketSettings()
                .withPeriodicKeepAliveData(() ->
                        ByteString.fromString(String.format("debug-%d", pingCounter.incrementAndGet()))
                );

        ClientConnectionSettings customSettings =
                defaultSettings.withWebsocketSettings(customWebsocketSettings);

        Http http = Http.get(system);
        http.singleWebSocketRequest(
                WebSocketRequest.create("ws://127.0.0.1"),
                clientFlow,
                ConnectionContext.noEncryption(),
                Optional.empty(),
                customSettings,
                system.log(),
                materializer
        );
        //#websocket-client-ping-payload
    }

    static Flow<Message, Message, NotUsed> createWebSocketFlow() {

        Source<Message, NotUsed> source = Source.<Outgoing>actorRef(5, OverflowStrategy.fail())
                .map((outgoing) -> (Message) TextMessage.create(outgoing.message))
                .<NotUsed>mapMaterializedValue(destinationRef -> {
                    destinationRefParent = destinationRef;
                    actor.tell(new OutgoingDestination(destinationRef), ActorRef.noSender());
                    return NotUsed.getInstance();
                }).keepAlive(Duration.ofSeconds(10), () -> TextMessage.create("Keep-alive message sent to WebSocket recipient"));


        Sink<Message, NotUsed> sink = Flow.<Message>create()
                .map((msg) -> new Incoming(msg.asTextMessage().getStrictText()))
                .to(Sink.actorRef(actor, PoisonPill.getInstance()));


        return Flow.fromSinkAndSource(sink, source);
    }
    //#websocket-handling
    public static HttpResponse handleRequest(HttpRequest request, Materializer materializer) {
        //System.out.println("Handling request to " + request.getUri());

        Sink<Message, CompletionStage<Done>> sink = Sink.foreach(e -> System.out.println(e.asTextMessage().getStrictText()));
        Flow<Message, Message, NotUsed> flow = Flow.fromSinkAndSource(sink, source);

        if (request.getUri().path().equals("/greeter")) {

            return WebSocket.handleWebSocketRequestWith(request,
                    createWebSocketFlow());

        }
        if (request.getUri().path().equals("/sendMessage")) {

            //actor.tell(new WebSocketCoreExample.Incoming("Hi"), ActorRef.noSender());

            destinationRefParent.tell(new Outgoing("got it sendMessage"), ActorRef.noSender());
            destinationRefParentStreams.tell(new Integer(1), ActorRef.noSender());

//            system.scheduler().schedule(Duration.ZERO, Duration.ofMillis(3000), actor,
//                    new WebSocketCoreExample.Incoming("Hi"), ActorRef.noSender());
            return HttpResponse.create().withStatus(200);
        } else {
            return HttpResponse.create().withStatus(404);
        }
    }

    private static void initializeStreams(Materializer materializer) {

        Source<Integer, NotUsed> source = Source.<Integer>actorRef(5000, OverflowStrategy.dropTail())
                .map(i -> i)
                .<NotUsed>mapMaterializedValue(destinationRef -> {
                    destinationRefParentStreams = destinationRef;
                    return NotUsed.getInstance();
                });

        Flow<Integer, Integer, NotUsed> sample = Flow.create();

        Sink<Integer, NotUsed> sink = sample.
                conflateWithSeed(Summed::new, (Summed acc, Integer el) -> acc.sum(new Summed(el))).
                zip(Source.tick(Duration.ofSeconds(1), Duration.ofSeconds(1), NotUsed.notUsed())).
                to(Sink.foreach(System.out::print)).named("sample");

        sample.runWith(source, sink, materializer);
    }

    public static void main(String[] args) throws Exception {
        system = ActorSystem.create();

        try {
            final Materializer materializer = ActorMaterializer.create(system);

            final Function<HttpRequest, HttpResponse> handler = request -> handleRequest(request, materializer);

            actor = system.actorOf(Props.create(AnActor.class));

            WebSocketCoreExample.initializeStreams(materializer);

            CompletionStage<ServerBinding> serverBindingFuture =
                    Http.get(system).bindAndHandleSync(
                            handler, ConnectHttp.toHost("localhost", 8080), materializer);


            // will throw if binding fails
            serverBindingFuture.toCompletableFuture().get(1, TimeUnit.SECONDS);
            System.out.println("Press ENTER to stop.");
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } finally {
            system.terminate();
        }
    }

    public static TextMessage handleTextMessage(TextMessage msg) {
        if (msg.isStrict()) // optimization that directly creates a simple response...
        {
            return TextMessage.create("Hello " + msg.getStrictText());
        } else // ... this would suffice to handle all text messages in a streaming fashion
        {
            return TextMessage.create(Source.single("Hello ").concat(msg.getStreamedText()));
        }
    }

    //#websocket-handling

    static class AnActor extends AbstractActor {

        private Optional<ActorRef> outgoing = Optional.empty();


        @Override
        public Receive createReceive() {
            return receiveBuilder().match(
                    OutgoingDestination.class, (msg) -> setActorRef(msg)
            ).match(
                    Incoming.class, (in) -> handleIncomingMessage(in)
            ).build();
        }

        private Optional<ActorRef> setActorRef(OutgoingDestination msg) {
            outgoing = Optional.ofNullable(msg.destination);
            return outgoing;
        }

        private void handleIncomingMessage(Incoming in) {
            outgoing.ifPresent((out) -> out.tell(new Outgoing("got it" + in.message), self()));
        }
    }

    //#websocket-handler

    static class Incoming {
        public final String message;

        public Incoming(String message) {
            this.message = message;
        }
    }

    static class Outgoing {
        public final String message;

        public Outgoing(String message) {
            this.message = message;
        }
    }
    //#websocket-handler

    static class OutgoingDestination {
        public final ActorRef destination;

        OutgoingDestination(ActorRef destination) {
            this.destination = destination;
        }
    }


}
//#websocket-example-using-core