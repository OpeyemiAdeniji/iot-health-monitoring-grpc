/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package distsystem.iot.health.monitoring.grpc;

import generated.grpc.healthmonitoring.Heartbeat;
import generated.grpc.healthmonitoring.HeartbeatSummary;
import generated.grpc.healthmonitoring.HealthRequest;
import generated.grpc.healthmonitoring.HealthStatus;
import generated.grpc.healthmonitoring.HealthMonitoringServiceGrpc.HealthMonitoringServiceImplBase;

import generated.grpc.deviceregistry.DeviceCheckRequest;
import generated.grpc.deviceregistry.DeviceCheckReply;
import generated.grpc.deviceregistry.DeviceRegistryServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;


//
///**
// *
// * @author opeyemiadeniji
// */
//

public class HealthMonitoringServer extends HealthMonitoringServiceImplBase {

    private static final Logger logger = Logger.getLogger(HealthMonitoringServer.class.getName());

    private static final Metadata.Key<String> FACTORY_ID_KEY =
            Metadata.Key.of("factory-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_TIMESTAMP_KEY =
            Metadata.Key.of("client-timestamp", Metadata.ASCII_STRING_MARSHALLER);

    // deadline for the inter-service call to the registry
    private static final int REGISTRY_DEADLINE_SECONDS = 3;

    // stores the last heartbeat time per device, used to calculate health state
    private static Map<String, Long> heartbeatMap = new HashMap<>();

    // gRPC connection to DeviceRegistryServer for inter-service communication
    private static ManagedChannel registryChannel;
    private static DeviceRegistryServiceGrpc.DeviceRegistryServiceBlockingStub registryStub;

    public static void main(String[] args) {

        HealthMonitoringServer healthMonitoringServer = new HealthMonitoringServer();
        int port = 50052;

        try {
            Server server = ServerBuilder.forPort(port)
                    .addService(healthMonitoringServer)
                    .intercept(new MetadataLoggerInterceptor())
                    .build()
                    .start();

            logger.info("HealthMonitoringServer started on port " + port);

            connectToRegistry();

            ServiceRegistration serviceRegistration = ServiceRegistration.getInstance();
            serviceRegistration.registerService(
                    "_grpc._tcp.local.",
                    "HealthMonitoringService",
                    port,
                    "Client and server streaming health monitoring service"
            );

            server.awaitTermination();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        } finally {
            if (registryChannel != null) registryChannel.shutdown();
        }
    }

    // discovers and connects to DeviceRegistryServer so heartbeats can be validated
    private static void connectToRegistry() {
        try {
            ServiceDiscovery registryDiscovery =
                    new ServiceDiscovery("_grpc._tcp.local.", "DeviceRegistryService");
            int registryPort = registryDiscovery.discoverService(10000);
            registryDiscovery.close();

            if (registryPort == -1) {
                logger.warning("Could not find DeviceRegistryServer - heartbeats accepted without validation.");
                return;
            }

            registryChannel = ManagedChannelBuilder.forAddress("localhost", registryPort)
                    .usePlaintext().build();
            registryStub = DeviceRegistryServiceGrpc.newBlockingStub(registryChannel);
            logger.info("Connected to DeviceRegistryServer on port " + registryPort);

        } catch (Exception e) {
            logger.warning("Failed to connect to DeviceRegistryServer: " + e.getMessage());
        }
    }

    // calls DeviceRegistryServer to check if a device is registered before accepting its heartbeat
    private boolean isDeviceRegistered(String deviceId) {
        if (registryStub == null) {
            logger.warning("Registry not available - accepting heartbeat from: " + deviceId);
            return true;
        }
        try {
            DeviceCheckReply reply = registryStub
                    .withDeadlineAfter(REGISTRY_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .checkDevice(DeviceCheckRequest.newBuilder().setDeviceId(deviceId).build());
            return reply.getExists();
        } catch (Exception e) {
            logger.warning("Registry check failed for " + deviceId + ": " + e.getMessage());
            return true; // fail open so heartbeats still work if registry is down
        }
    }

    // Client Streaming RPC - receives multiple heartbeats, responds with a summary
    @Override
    public StreamObserver<Heartbeat> uploadHeartbeats(StreamObserver<HeartbeatSummary> responseObserver) {

        return new StreamObserver<Heartbeat>() {

            int totalHeartbeats    = 0;
            int rejectedHeartbeats = 0;
            Set<String> uniqueDevices = new HashSet<>();

            @Override
            public void onNext(Heartbeat heartbeat) {

                String deviceId = heartbeat.getDeviceId();
                long timestamp  = heartbeat.getTimestamp();

                if (deviceId == null || deviceId.isBlank()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Device ID cannot be empty").asRuntimeException());
                    return;
                }

                if (timestamp <= 0) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Timestamp must be a valid positive value").asRuntimeException());
                    return;
                }

                // check with registry before accepting this heartbeat
                if (!isDeviceRegistered(deviceId)) {
                    logger.warning("Rejected heartbeat from unregistered device: " + deviceId);
                    rejectedHeartbeats++;
                    return;
                }

                logger.info("Accepted heartbeat from " + deviceId + " at " + timestamp);
                totalHeartbeats++;
                uniqueDevices.add(deviceId);
                heartbeatMap.put(deviceId, timestamp);
            }

            @Override
            public void onError(Throwable throwable) {
                logger.warning("Error in heartbeat stream: " + throwable.getMessage());
            }

            @Override
            public void onCompleted() {
                // build a message explaining how many heartbeats were accepted or rejected
                String summaryMessage;
                if (totalHeartbeats == 0 && rejectedHeartbeats > 0) {
                    summaryMessage = "All " + rejectedHeartbeats
                            + " heartbeat(s) rejected - devices are not registered. "
                            + "Please register your devices first.";
                } else if (rejectedHeartbeats > 0) {
                    summaryMessage = totalHeartbeats + " heartbeat(s) accepted, "
                            + rejectedHeartbeats + " rejected (unregistered devices).";
                } else {
                    summaryMessage = "All " + totalHeartbeats + " heartbeat(s) accepted successfully.";
                }

                responseObserver.onNext(HeartbeatSummary.newBuilder()
                        .setTotalHeartbeats(totalHeartbeats)
                        .setUniqueDevices(uniqueDevices.size())
                        .setRejectedHeartbeats(rejectedHeartbeats)
                        .setMessage(summaryMessage)
                        .build());
                responseObserver.onCompleted();

                logger.info("Heartbeat stream done - accepted: " + totalHeartbeats
                        + ", rejected: " + rejectedHeartbeats);
            }
        };
    }

    // Server Streaming RPC - receives one request, streams back one status per device
    @Override
    public void streamHealth(HealthRequest request, StreamObserver<HealthStatus> responseObserver) {

        String groupId = request.getGroupId();

        if (groupId == null || groupId.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Group ID cannot be empty").asRuntimeException());
            return;
        }

        logger.info("Health stream requested for group: " + groupId);

        if (heartbeatMap.isEmpty()) {
            logger.info("No devices found - nothing to stream");
            responseObserver.onCompleted();
            return;
        }

        try {
            for (Map.Entry<String, Long> entry : heartbeatMap.entrySet()) {

                String deviceId    = entry.getKey();
                long diff          = System.currentTimeMillis() - entry.getValue();

                // state is based on how long ago the last heartbeat was received
                String state;
                if      (diff < 10000) state = "OK";
                else if (diff < 20000) state = "LATE";
                else                   state = "OFFLINE";

                responseObserver.onNext(HealthStatus.newBuilder()
                        .setDeviceId(deviceId)
                        .setState(state)
                        .build());

                Thread.sleep(1000);
            }
            responseObserver.onCompleted();

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // reads metadata headers from every incoming request and logs them
    static class MetadataLoggerInterceptor implements ServerInterceptor {

        private static final Logger interceptorLogger =
                Logger.getLogger(MetadataLoggerInterceptor.class.getName());

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {

            String factoryId       = headers.get(FACTORY_ID_KEY);
            String clientTimestamp = headers.get(CLIENT_TIMESTAMP_KEY);

            interceptorLogger.info("Request received"
                    + " | factory-id: " + (factoryId != null ? factoryId : "not set")
                    + " | client-timestamp: " + (clientTimestamp != null ? clientTimestamp : "not set")
                    + " | method: " + call.getMethodDescriptor().getFullMethodName());

            return next.startCall(call, headers);
        }
    }
}