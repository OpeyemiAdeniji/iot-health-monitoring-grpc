/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package distsystem.iot.health.monitoring.grpc;

import generated.grpc.devicealert.DeviceAlert;
import generated.grpc.devicealert.DeviceAction;
import generated.grpc.devicealert.DeviceAlertServiceGrpc.DeviceAlertServiceImplBase;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.logging.Logger;

/**
 *
 * @author opeyemiadeniji
 */

// gRPC server for handling real time device alerts and responding with actions
public class DeviceAlertServer extends DeviceAlertServiceImplBase {

    private static final Logger logger = Logger.getLogger(DeviceAlertServer.class.getName());
    
    // metadata keys used to read the factory id and timestamp sent by the client
    private static final Metadata.Key<String> FACTORY_ID_KEY
            = Metadata.Key.of("factory-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_TIMESTAMP_KEY
            = Metadata.Key.of("client-timestamp", Metadata.ASCII_STRING_MARSHALLER);

    // starts the alert server and registers it via JmDNS
    public static void main(String[] args) {

        DeviceAlertServer deviceAlertServer = new DeviceAlertServer();
        int port = 50053;

        try {
            Server server = ServerBuilder.forPort(port)
                    .addService(deviceAlertServer)
                    .intercept(new MetadataLoggerInterceptor())
                    .build()
                    .start();

            logger.info("DeviceAlertServer started on port " + port);

            ServiceRegistration serviceRegistration = ServiceRegistration.getInstance();
            serviceRegistration.registerService(
                    "_grpc._tcp.local.",
                    "DeviceAlertService",
                    port,
                    "Bidirectional device alert service"
            );

            server.awaitTermination();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    // handles bidirectional streaming of device alerts and sends back actions
    @Override
    public StreamObserver<DeviceAlert> liveDeviceAlerts(StreamObserver<DeviceAction> responseObserver) {

        logger.info("Bidi alert session opened");

        return new StreamObserver<DeviceAlert>() {

            @Override
            public void onNext(DeviceAlert request) {

                String deviceId = request.getDeviceId();
                String issue = request.getIssue();

                if (deviceId == null || deviceId.isBlank()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Device ID cannot be empty").asRuntimeException());
                    return;
                }

                if (issue == null || issue.isBlank()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Issue cannot be empty").asRuntimeException());
                    return;
                }

                logger.info("[BIDI] Alert from " + deviceId + ": " + issue);

                String action;
                switch (issue.toUpperCase()) {
                    case "NO_HEARTBEAT":
                        action = "RESTART";
                        break;
                    case "NETWORK_FAILURE":
                        action = "CHECK_NETWORK";
                        break;
                    case "HIGH_TEMPERATURE":
                        action = "REDUCE_LOAD";
                        break;
                    case "LOW_BATTERY":
                        action = "RECHARGE";
                        break;
                    default:
                        action = "ESCALATE";
                }

                logger.info("[BIDI] Action for " + deviceId + ": " + action);
                responseObserver.onNext(DeviceAction.newBuilder()
                        .setDeviceId(deviceId)
                        .setAction(action)
                        .build());
            }

            @Override
            public void onError(Throwable throwable) {
                logger.warning("Alert session error: " + throwable.getMessage());
            }

            @Override
            public void onCompleted() {
                // client closed its side so we close ours too
                logger.info("Alert session closed by client");
                responseObserver.onCompleted();
            }
        };
    }

    // intercepts requests to log incoming metadata
    static class MetadataLoggerInterceptor implements ServerInterceptor {

        private static final Logger interceptorLogger
                = Logger.getLogger(MetadataLoggerInterceptor.class.getName());

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {

            String factoryId = headers.get(FACTORY_ID_KEY);
            String clientTimestamp = headers.get(CLIENT_TIMESTAMP_KEY);

            interceptorLogger.info("Request received"
                    + " | factory-id: " + (factoryId != null ? factoryId : "not set")
                    + " | client-timestamp: " + (clientTimestamp != null ? clientTimestamp : "not set")
                    + " | method: " + call.getMethodDescriptor().getFullMethodName());

            return next.startCall(call, headers);
        }
    }
}
