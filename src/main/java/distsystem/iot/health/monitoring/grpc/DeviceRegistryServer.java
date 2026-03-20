/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package distsystem.iot.health.monitoring.grpc;


/**
 *
 * @author opeyemiadeniji
 */

// DeviceRegistryService - Unary RPC
// This service handles device registration for the smart factory system
// When a new device comes online it must register here before it can
// send heartbeats to the health monitoring service
//
// I also added a CheckDevice RPC that the HealthMonitoringServer calls
// internally to verify a device is registered before accepting its heartbeat
// This is inter-service communication - one server calling another

import generated.grpc.deviceregistry.DeviceInfo;
import generated.grpc.deviceregistry.RegisterReply;
import generated.grpc.deviceregistry.DeviceCheckRequest;
import generated.grpc.deviceregistry.DeviceCheckReply;
import generated.grpc.deviceregistry.DeviceRegistryServiceGrpc.DeviceRegistryServiceImplBase;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class DeviceRegistryServer extends DeviceRegistryServiceImplBase {

    private static final Logger logger = Logger.getLogger(DeviceRegistryServer.class.getName());

    // metadata keys - used to read the factory-id and timestamp sent by the client
    private static final Metadata.Key<String> FACTORY_ID_KEY =
            Metadata.Key.of("factory-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLIENT_TIMESTAMP_KEY =
            Metadata.Key.of("client-timestamp", Metadata.ASCII_STRING_MARSHALLER);

    // I used ConcurrentHashMap instead of HashMap because the HealthMonitoringServer
    // can call checkDevice at the same time as a registration is happening
    // ConcurrentHashMap handles that safely without needing manual synchronisation
    static Map<String, String> deviceRegistry = new ConcurrentHashMap<>();

    public static void main(String[] args) {

        DeviceRegistryServer deviceRegistryServer = new DeviceRegistryServer();
        int port = 50051;

        try {
            Server server = ServerBuilder.forPort(port)
                    .addService(deviceRegistryServer)
                    .intercept(new MetadataLoggerInterceptor()) // log metadata from each request
                    .build()
                    .start();

            logger.info("DeviceRegistryServer started on port " + port);

            // register with mDNS so the client can find this service automatically
            ServiceRegistration serviceRegistration = ServiceRegistration.getInstance();
            serviceRegistration.registerService(
                    "_grpc._tcp.local.",
                    "DeviceRegistryService",
                    port,
                    "Unary device registration service"
            );

            server.awaitTermination();

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Unary RPC - RegisterDevice
    // client sends a DeviceInfo, server stores it and replies with a message
    // returns an error if the device id is empty, type is empty, or already registered
    @Override
    public void registerDevice(DeviceInfo request, StreamObserver<RegisterReply> responseObserver) {

        String deviceId   = request.getDeviceId();
        String deviceType = request.getDeviceType();

        if (deviceId == null || deviceId.isBlank()) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription("Device ID cannot be empty")
                            .asRuntimeException()
            );
            return;
        }

        if (deviceType == null || deviceType.isBlank()) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription("Device type cannot be empty")
                            .asRuntimeException()
            );
            return;
        }

        // don't allow the same device to register twice
        if (deviceRegistry.containsKey(deviceId)) {
            responseObserver.onError(
                    Status.ALREADY_EXISTS
                            .withDescription("Device " + deviceId + " is already registered")
                            .asRuntimeException()
            );
            return;
        }

        deviceRegistry.put(deviceId, deviceType);
        logger.info("Registered: " + deviceId + " (" + deviceType + ")");

        RegisterReply response = RegisterReply.newBuilder()
                .setSuccess(true)
                .setMessage("Device " + deviceId + " registered successfully")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    // Unary RPC - CheckDevice
    // this is called by HealthMonitoringServer to check if a device is registered
    // before accepting its heartbeat - this is the inter-service communication part
    @Override
    public void checkDevice(DeviceCheckRequest request, StreamObserver<DeviceCheckReply> responseObserver) {

        String deviceId = request.getDeviceId();

        if (deviceId == null || deviceId.isBlank()) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription("Device ID cannot be empty")
                            .asRuntimeException()
            );
            return;
        }

        boolean exists = deviceRegistry.containsKey(deviceId);
        logger.info("CheckDevice: " + deviceId + " -> " + (exists ? "REGISTERED" : "NOT REGISTERED"));

        DeviceCheckReply reply = DeviceCheckReply.newBuilder()
                .setExists(exists)
                .setMessage(exists
                        ? "Device " + deviceId + " is registered"
                        : "Device " + deviceId + " is not registered")
                .build();

        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    // interceptor that runs before every RPC call
    // it reads the metadata headers sent by the client and logs them
    // this shows how metadata flows from client to server in gRPC
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