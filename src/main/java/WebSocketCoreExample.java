//#websocket-example-using-core

import akka.Done;
import akka.NotUsed;
import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
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
import akka.japi.JavaPartialFunction;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.AsPublisher;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.reactivestreams.Publisher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings({"Convert2MethodRef", "ConstantConditions"})
public class WebSocketCoreExample {

    public static Source<Message, ActorRef> source = Source.actorRef(100, OverflowStrategy.dropHead());
    public static ActorRef actorRef = null;
    public static ActorSystem system = null;

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
        ActorRef actor = system.actorOf(Props.create(AnActor.class));

        Source<Message, NotUsed> source = Source.<Outgoing>actorRef(5, OverflowStrategy.fail())
                .map((outgoing) -> (Message) TextMessage.create(outgoing.message))
                .<NotUsed>mapMaterializedValue(destinationRef -> {
                    actor.tell(new OutgoingDestination(destinationRef), ActorRef.noSender());
                    return NotUsed.getInstance();
                });

        Sink<Message, NotUsed> sink = Flow.<Message>create()
                .map((msg) -> new Incoming(msg.asTextMessage().getStrictText()))
                .to(Sink.actorRef(actor, PoisonPill.getInstance()));


        return Flow.fromSinkAndSource(sink, source);
    }

    //#websocket-handling
    public static HttpResponse handleRequest(HttpRequest request, Materializer materializer) {
        System.out.println("Handling request to " + request.getUri());


        if (actorRef == null) {
            actorRef = source.to(Sink.foreach(e -> System.out.println(e.asTextMessage().getStrictText()))).run(materializer);

            Sink<Message, Publisher<Message>> publisher = Sink.asPublisher(AsPublisher.WITHOUT_FANOUT);
            source.to(Sink.asPublisher(AsPublisher.WITHOUT_FANOUT)).run(materializer);

            //Source.fromPublisher(publisher);

            actorRef.tell(TextMessage.create("Hello"), ActorRef.noSender());
        }

        //Source<Message, NotUsed> source = Source.single(TextMessage.create("Hai Single message"));
        Sink<Message, CompletionStage<Done>> sink = Sink.foreach(e -> System.out.println(e.asTextMessage().getStrictText()));
        Flow<Message, Message, NotUsed> flow = Flow.fromSinkAndSource(sink, source);

        if (request.getUri().path().equals("/greeter")) {

            //Source<Message,ActorRef> source = Source.actorPublisher(IotSupervisor.props());
            //final Flow<Message, Message, NotUsed> greeterFlow = greeter();
            return WebSocket.handleWebSocketRequestWith(request,
                    createWebSocketFlow());

            //return WebSocket.handleWebSocketRequestWith(request, greeterFlow);
        }
        if (request.getUri().path().equals("/sendMessage")) {

            ActorRef supervisor = system.actorOf(IotSupervisor.props(), "iot");
            //source.watch()
            supervisor.tell(TextMessage.create("Hello"), ActorRef.noSender());

            return HttpResponse.create().withStatus(200);
        } else {
            return HttpResponse.create().withStatus(404);
        }
    }

    public static void main(String[] args) throws Exception {
        system = ActorSystem.create();

        try {
            final Materializer materializer = ActorMaterializer.create(system);

            final Function<HttpRequest, HttpResponse> handler = request -> handleRequest(request, materializer);
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

    /**
     * A handler that treats incoming messages as a name,
     * and responds with a greeting to that name
     */
    public static Flow<Message, Message, NotUsed> greeter() {
        return
                Flow.<Message>create()
                        .collect(new JavaPartialFunction<Message, Message>() {
                            @Override
                            public Message apply(Message msg, boolean isCheck) throws Exception {
                                if (isCheck) {
                                    if (msg.isText()) {
                                        return null;
                                    } else {
                                        throw noMatch();
                                    }
                                } else {
                                    return handleTextMessage(msg.asTextMessage());
                                }
                            }
                        });
    }
    //#websocket-handling

    public static TextMessage handleTextMessage(TextMessage msg) {
        if (msg.isStrict()) // optimization that directly creates a simple response...
        {
            return TextMessage.create("Hello " + msg.getStrictText());
        } else // ... this would suffice to handle all text messages in a streaming fashion
        {
            return TextMessage.create(Source.single("Hello ").concat(msg.getStreamedText()));
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

    static class AnActor extends AbstractActor {

        private Optional<ActorRef> outgoing = Optional.empty();

        @Override
        public Receive createReceive() {
            return receiveBuilder().match(
                    OutgoingDestination.class, (msg) -> outgoing = Optional.of(msg.destination)
            ).match(
                    Incoming.class, (in) -> outgoing.ifPresent((out) -> out.tell(new Outgoing("got it"), self()))
            ).build();
        }
    }

    public static class IotSupervisor extends AbstractActor {
        private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

        public static Props props() {
            return Props.create(IotSupervisor.class, IotSupervisor::new);
        }

        @Override
        public void preStart() {
            log.info("IoT Application started");
        }

        @Override
        public void postStop() {
            log.info("IoT Application stopped");
        }

        // No need to handle any messages
        @Override
        public Receive createReceive() {
            return receiveBuilder().build();
        }
    }
}
//#websocket-example-using-core