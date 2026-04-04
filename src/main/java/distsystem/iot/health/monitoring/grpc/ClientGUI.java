/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package distsystem.iot.health.monitoring.grpc;

import generated.grpc.deviceregistry.DeviceInfo;
import generated.grpc.deviceregistry.DeviceRegistryServiceGrpc;
import generated.grpc.deviceregistry.RegisterReply;

import generated.grpc.healthmonitoring.Heartbeat;
import generated.grpc.healthmonitoring.HeartbeatSummary;
import generated.grpc.healthmonitoring.HealthMonitoringServiceGrpc;
import generated.grpc.healthmonitoring.HealthRequest;
import generated.grpc.healthmonitoring.HealthStatus;

import generated.grpc.devicealert.DeviceAlert;
import generated.grpc.devicealert.DeviceAction;
import generated.grpc.devicealert.DeviceAlertServiceGrpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author opeyemiadeniji
 */
public class ClientGUI extends JFrame {

    // inner class that handles all gRPC communication
    static class IotClient {

        // metadata keys sent with every request to identify the factory and connection time
        private static final Metadata.Key<String> FACTORY_ID_KEY
                = Metadata.Key.of("factory-id", Metadata.ASCII_STRING_MARSHALLER);
        private static final Metadata.Key<String> CLIENT_TIMESTAMP_KEY
                = Metadata.Key.of("client-timestamp", Metadata.ASCII_STRING_MARSHALLER);

        // deadline in seconds applied to blocking calls so the client doesn't hang
        private static final int DEADLINE_SECONDS = 5;

        private int registryPort = -1;
        private int healthPort = -1;
        private int alertPort = -1;

        private ManagedChannel registryChannel;
        private ManagedChannel healthChannel;
        private ManagedChannel alertChannel;

        private DeviceRegistryServiceGrpc.DeviceRegistryServiceBlockingStub registryBlockingStub;
        private HealthMonitoringServiceGrpc.HealthMonitoringServiceStub healthAsyncStub;
        private DeviceAlertServiceGrpc.DeviceAlertServiceStub alertAsyncStub;

        private boolean connected = false;

        // keeps the bidi alert stream open across multiple sendAlert calls
        private StreamObserver<DeviceAlert> alertRequestObserver = null;
        private boolean alertSessionOpen = false;

        // connects on first use, reuses the connection after that
        public void ensureConnected() throws Exception {
            if (!connected) {
                discoverServices();
                connect();
                connected = true;
            }
        }

        // uses JmDNS to find each server by name so no ports are hardcoded
        private void discoverServices() throws Exception {
            ServiceDiscovery registryDiscovery
                    = new ServiceDiscovery("_grpc._tcp.local.", "DeviceRegistryService");
            ServiceDiscovery healthDiscovery
                    = new ServiceDiscovery("_grpc._tcp.local.", "HealthMonitoringService");
            ServiceDiscovery alertDiscovery
                    = new ServiceDiscovery("_grpc._tcp.local.", "DeviceAlertService");

            registryPort = registryDiscovery.discoverService(10000);
            healthPort = healthDiscovery.discoverService(10000);
            alertPort = alertDiscovery.discoverService(10000);

            System.out.println("Discovered - Registry: " + registryPort
                    + ", Health: " + healthPort + ", Alert: " + alertPort);

            registryDiscovery.close();
            healthDiscovery.close();
            alertDiscovery.close();

            if (registryPort == -1 || healthPort == -1 || alertPort == -1) {
                throw new IOException("Could not discover one or more services.");
            }
        }

        // builds channels and attaches metadata to every outgoing request via an interceptor
        private void connect() {
            Metadata headers = new Metadata();
            headers.put(FACTORY_ID_KEY, "FACTORY-1");
            headers.put(CLIENT_TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));

            // interceptor merges the metadata headers into every outgoing call
            ClientInterceptor metadataInterceptor = new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                        MethodDescriptor<ReqT, RespT> method,
                        CallOptions callOptions,
                        Channel next) {
                    return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                            next.newCall(method, callOptions)) {
                        @Override
                        public void start(Listener<RespT> responseListener, Metadata requestHeaders) {
                            requestHeaders.merge(headers);
                            super.start(responseListener, requestHeaders);
                        }
                    };
                }
            };

            registryChannel = ManagedChannelBuilder.forAddress("localhost", registryPort)
                    .usePlaintext().intercept(metadataInterceptor).build();
            healthChannel = ManagedChannelBuilder.forAddress("localhost", healthPort)
                    .usePlaintext().intercept(metadataInterceptor).build();
            alertChannel = ManagedChannelBuilder.forAddress("localhost", alertPort)
                    .usePlaintext().intercept(metadataInterceptor).build();

            registryBlockingStub = DeviceRegistryServiceGrpc.newBlockingStub(registryChannel);
            healthAsyncStub = HealthMonitoringServiceGrpc.newStub(healthChannel);
            alertAsyncStub = DeviceAlertServiceGrpc.newStub(alertChannel);
        }

        // closes all channels and any open alert stream cleanly
        public void shutdown() {
            endAlertSession();
            if (registryChannel != null) {
                registryChannel.shutdown();
            }
            if (healthChannel != null) {
                healthChannel.shutdown();
            }
            if (alertChannel != null) {
                alertChannel.shutdown();
            }
            connected = false;
        }

        // Unary RPC - sends one request, gets one response back
        public String registerDevice(String deviceId, String deviceType) throws Exception {
            ensureConnected();
            DeviceInfo request = DeviceInfo.newBuilder()
                    .setDeviceId(deviceId)
                    .setDeviceType(deviceType)
                    .build();
            RegisterReply response = registryBlockingStub
                    .withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .registerDevice(request);
            return response.getMessage();
        }

        // Client Streaming RPC - streams multiple heartbeats, gets one summary back
        public String uploadHeartbeats(String[] deviceIds) throws Exception {
            ensureConnected();

            CountDownLatch latch = new CountDownLatch(1);
            final StringBuilder result = new StringBuilder();

            StreamObserver<HeartbeatSummary> responseObserver = new StreamObserver<HeartbeatSummary>() {
                @Override
                public void onNext(HeartbeatSummary summary) {
                    result.append(summary.getMessage())
                            .append(" (Total: ").append(summary.getTotalHeartbeats())
                            .append(", Unique: ").append(summary.getUniqueDevices())
                            .append(", Rejected: ").append(summary.getRejectedHeartbeats())
                            .append(")");
                }

                @Override
                public void onError(Throwable t) {
                    result.append("Heartbeat error: ").append(t.getMessage());
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }
            };

            StreamObserver<Heartbeat> requestObserver
                    = healthAsyncStub.uploadHeartbeats(responseObserver);

            for (String id : deviceIds) {
                String cleanedId = id.trim();
                if (!cleanedId.isEmpty()) {
                    requestObserver.onNext(Heartbeat.newBuilder()
                            .setDeviceId(cleanedId)
                            .setTimestamp(System.currentTimeMillis())
                            .build());
                }
            }

            requestObserver.onCompleted();
            latch.await(DEADLINE_SECONDS, TimeUnit.SECONDS);
            return result.toString();
        }

        // Server Streaming RPC - sends one request, receives multiple status responses
        public void streamHealth(String groupId, JTextArea outputArea) {
            try {
                ensureConnected();
            } catch (Exception e) {
                outputArea.append("Connection error: " + e.getMessage() + "\n");
                return;
            }

            HealthRequest request = HealthRequest.newBuilder()
                    .setGroupId(groupId).build();

            healthAsyncStub.streamHealth(request, new StreamObserver<HealthStatus>() {
                @Override
                public void onNext(HealthStatus status) {
                    outputArea.append("Device: " + status.getDeviceId()
                            + " | State: " + status.getState() + "\n");
                }

                @Override
                public void onError(Throwable t) {
                    outputArea.append("Health stream error: " + t.getMessage() + "\n");
                }

                @Override
                public void onCompleted() {
                    outputArea.append("Health stream completed.\n");
                }
            });
        }

        // Bidirectional Streaming RPC - opens the stream so alerts can be sent
        public void openAlertSession(JTextArea outputArea) {
            try {
                ensureConnected();
            } catch (Exception e) {
                outputArea.append("Connection error: " + e.getMessage() + "\n");
                return;
            }

            if (alertSessionOpen) {
                outputArea.append("Alert session is already open.\n");
                return;
            }

            StreamObserver<DeviceAction> responseObserver = new StreamObserver<DeviceAction>() {
                @Override
                public void onNext(DeviceAction action) {
                    outputArea.append("[SERVER -> CLIENT] Action for "
                            + action.getDeviceId() + ": " + action.getAction() + "\n");
                }

                @Override
                public void onError(Throwable t) {
                    outputArea.append("Alert session error: " + t.getMessage() + "\n");
                    alertSessionOpen = false;
                    alertRequestObserver = null;
                }

                @Override
                public void onCompleted() {
                    outputArea.append("Alert session closed by server.\n");
                    alertSessionOpen = false;
                    alertRequestObserver = null;
                }
            };

            alertRequestObserver = alertAsyncStub.liveDeviceAlerts(responseObserver);
            alertSessionOpen = true;
            outputArea.append("Alert session opened. You can now send alerts.\n");
        }

        // sends one alert on the open stream, server responds immediately
        public void sendAlert(String deviceId, String issue, JTextArea outputArea) {
            if (!alertSessionOpen || alertRequestObserver == null) {
                outputArea.append("No alert session open. Click 'Open Session' first.\n");
                return;
            }
            outputArea.append("[CLIENT -> SERVER] Alert - device: "
                    + deviceId + ", issue: " + issue + "\n");
            alertRequestObserver.onNext(DeviceAlert.newBuilder()
                    .setDeviceId(deviceId)
                    .setIssue(issue)
                    .build());
        }

        // closes the client side of the stream
        public void endAlertSession() {
            if (alertSessionOpen && alertRequestObserver != null) {
                alertRequestObserver.onCompleted();
                alertSessionOpen = false;
                alertRequestObserver = null;
            }
        }

        public boolean isAlertSessionOpen() {
            return alertSessionOpen;
        }
    }

    // GUI fields
    private final IotClient client;

    private JTextField registerDeviceIdField;
    private JTextField registerDeviceTypeField;
    private JButton registerButton;

    private JTextField heartbeatDevicesField;
    private JButton heartbeatButton;

    private JTextField groupIdField;
    private JButton healthButton;

    private JTextField alertDeviceIdField;
    private JTextField alertIssueField;
    private JButton openSessionButton;
    private JButton sendAlertButton;
    private JButton endSessionButton;

    private JTextPane outputPane;
    private StyledDocument outputDoc;
    private JLabel statusLabel;

    private static final Color COLOR_OK = new Color(0, 160, 80);
    private static final Color COLOR_LATE = new Color(200, 120, 0);
    private static final Color COLOR_CLIENT = new Color(30, 110, 200);
    private static final Color COLOR_SERVER = new Color(120, 50, 180);
    private static final Color COLOR_DEFAULT = new Color(30, 30, 30);
    private static final Color COLOR_ERROR = new Color(190, 30, 30);
    private static final Color COLOR_INFO = new Color(80, 80, 80);

    public ClientGUI() {
        client = new IotClient();

        setTitle("Industrial IoT Health Monitoring System - SDG 9");
        setSize(900, 820);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        statusLabel = new JLabel("  * Not connected", SwingConstants.LEFT);
        statusLabel.setForeground(Color.GRAY);
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusLabel, BorderLayout.NORTH);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.add(createRegisterPanel());
        mainPanel.add(createHeartbeatPanel());
        mainPanel.add(createHealthPanel());
        mainPanel.add(createAlertPanel());

        JScrollPane mainScroll = new JScrollPane(mainPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        mainScroll.setBorder(null);

        outputPane = new JTextPane();
        outputPane.setEditable(false);
        outputDoc = outputPane.getStyledDocument();

        JScrollPane outputScroll = new JScrollPane(outputPane);
        outputScroll.setPreferredSize(new Dimension(860, 220));
        outputScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "System Output", TitledBorder.LEFT, TitledBorder.TOP));

        JButton clearButton = new JButton("Clear Output");
        clearButton.addActionListener(e -> {
            try {
                outputDoc.remove(0, outputDoc.getLength());
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        });

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(outputScroll, BorderLayout.CENTER);
        bottomPanel.add(clearButton, BorderLayout.SOUTH);

        add(mainScroll, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                client.shutdown();
            }
        });

        updateAlertSessionButtons();
    }

    // panel for registering a device - Unary RPC
    private JPanel createRegisterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "1. Register Device (Unary RPC)"));
        GridBagConstraints gbc = baseConstraints();

        registerDeviceIdField = new JTextField(20);
        registerDeviceTypeField = new JTextField(20);
        registerButton = new JButton("Register Device");
        registerButton.addActionListener(e -> registerDevice());

        addRow(panel, gbc, 0, "Device ID:", registerDeviceIdField);
        addRow(panel, gbc, 1, "Device Type:", registerDeviceTypeField);
        addWideButton(panel, gbc, 2, registerButton);
        return panel;
    }

    // panel for sending heartbeats - Client Streaming RPC
    private JPanel createHeartbeatPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "2. Send Heartbeats (Client Streaming RPC)"));
        GridBagConstraints gbc = baseConstraints();

        heartbeatDevicesField = new JTextField(30);
        heartbeatButton = new JButton("Send Heartbeats");
        heartbeatButton.addActionListener(e -> sendHeartbeats());

        addRow(panel, gbc, 0, "Device IDs (comma separated):", heartbeatDevicesField);
        addWideButton(panel, gbc, 1, heartbeatButton);
        return panel;
    }

    // panel for streaming health status - Server Streaming RPC
    private JPanel createHealthPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "3. Stream Health (Server Streaming RPC)"));
        GridBagConstraints gbc = baseConstraints();

        groupIdField = new JTextField("FACTORY-1", 20);
        healthButton = new JButton("Stream Health");
        healthButton.addActionListener(e -> streamHealth());

        addRow(panel, gbc, 0, "Group ID:", groupIdField);
        addWideButton(panel, gbc, 1, healthButton);
        return panel;
    }

    // panel for live alerts - Bidirectional Streaming RPC
    private JPanel createAlertPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "4. Live Device Alerts (Bidirectional Streaming RPC)"));
        GridBagConstraints gbc = baseConstraints();

        alertDeviceIdField = new JTextField(20);
        alertIssueField = new JTextField(20);

        openSessionButton = new JButton("Open Session");
        sendAlertButton = new JButton("Send Alert");
        endSessionButton = new JButton("End Session");

        openSessionButton.addActionListener(e -> openAlertSession());
        sendAlertButton.addActionListener(e -> sendAlert());
        endSessionButton.addActionListener(e -> endAlertSession());

        addRow(panel, gbc, 0, "Device ID:", alertDeviceIdField);
        addRow(panel, gbc, 1, "Issue:", alertIssueField);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonRow.add(openSessionButton);
        buttonRow.add(sendAlertButton);
        buttonRow.add(endSessionButton);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        panel.add(buttonRow, gbc);
        return panel;
    }

    private GridBagConstraints baseConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        return gbc;
    }

    private void addRow(JPanel panel, GridBagConstraints gbc,
            int row, String label, JComponent field) {
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        panel.add(field, gbc);
    }

    private void addWideButton(JPanel panel, GridBagConstraints gbc,
            int row, JButton button) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        panel.add(button, gbc);
        gbc.gridwidth = 1;
    }

    // updates the status label at the top of the window
    private void setStatus(String text, Color colour) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("  * " + text);
            statusLabel.setForeground(colour);
        });
    }

    // appends coloured text to the output pane and scrolls to the bottom
    private void appendColoured(String text, Color colour) {
        SwingUtilities.invokeLater(() -> {
            try {
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setForeground(attrs, colour);
                StyleConstants.setFontFamily(attrs, "Monospaced");
                StyleConstants.setFontSize(attrs, 12);
                outputDoc.insertString(outputDoc.getLength(), text + "\n", attrs);
                outputPane.setCaretPosition(outputDoc.getLength());
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        });
    }

    // picks a colour based on keywords in the output line
    private void appendSmart(String text) {
        String upper = text.toUpperCase();
        Color colour;
        if (upper.contains("STATE: OK") || upper.contains("SUCCESSFULLY")) {
            colour = COLOR_OK;
        } else if (upper.contains("STATE: LATE")) {
            colour = COLOR_LATE;
        } else if (upper.contains("STATE: OFFLINE") || upper.contains("ERROR")) {
            colour = COLOR_ERROR;
        } else if (upper.contains("[CLIENT ->")) {
            colour = COLOR_CLIENT;
        } else if (upper.contains("[SERVER ->")) {
            colour = COLOR_SERVER;
        } else if (upper.contains("COMPLETED") || upper.contains("OPENED")
                || upper.contains("CLOSED") || upper.contains("ENDED")) {
            colour = COLOR_INFO;
        } else {
            colour = COLOR_DEFAULT;
        }
        appendColoured(text, colour);
    }

    // enables/disables the three alert buttons based on session state
    private void updateAlertSessionButtons() {
        boolean open = client.isAlertSessionOpen();
        openSessionButton.setEnabled(!open);
        sendAlertButton.setEnabled(open);
        endSessionButton.setEnabled(open);
    }

    // runs registration on a background thread so the GUI stays responsive
    private void registerDevice() {
        String deviceId = registerDeviceIdField.getText().trim();
        String deviceType = registerDeviceTypeField.getText().trim();

        if (deviceId.isEmpty()) {
            appendColoured("Please enter a Device ID.", COLOR_ERROR);
            return;
        }
        if (deviceType.isEmpty()) {
            appendColoured("Please enter a Device Type.", COLOR_ERROR);
            return;
        }

        registerButton.setEnabled(false);
        setStatus("Registering device...", Color.ORANGE);

        new Thread(() -> {
            try {
                String result = client.registerDevice(deviceId, deviceType);
                appendSmart(result);
                setStatus("Connected", COLOR_OK);
            } catch (Exception ex) {
                appendColoured("Register error: " + ex.getMessage(), COLOR_ERROR);
                setStatus("Error", COLOR_ERROR);
            } finally {
                SwingUtilities.invokeLater(() -> registerButton.setEnabled(true));
            }
        }).start();
    }

    // runs heartbeat upload on a background thread
    private void sendHeartbeats() {
        String heartbeatText = heartbeatDevicesField.getText().trim();
        if (heartbeatText.isEmpty()) {
            appendColoured("Please enter at least one device ID.", COLOR_ERROR);
            return;
        }

        heartbeatButton.setEnabled(false);
        setStatus("Sending heartbeats...", Color.ORANGE);

        new Thread(() -> {
            try {
                String result = client.uploadHeartbeats(heartbeatText.split(","));
                appendSmart(result);
                setStatus("Connected", COLOR_OK);
            } catch (Exception ex) {
                appendColoured("Heartbeat error: " + ex.getMessage(), COLOR_ERROR);
                setStatus("Error", COLOR_ERROR);
            } finally {
                SwingUtilities.invokeLater(() -> heartbeatButton.setEnabled(true));
            }
        }).start();
    }

    // starts the health stream and routes responses to the output pane
    private void streamHealth() {
        String groupId = groupIdField.getText().trim();
        if (groupId.isEmpty()) {
            appendColoured("Please enter a Group ID.", COLOR_ERROR);
            return;
        }

        healthButton.setEnabled(false);
        setStatus("Streaming health data...", Color.ORANGE);

        JTextArea proxy = new JTextArea() {
            @Override
            public void append(String str) {
                String cleaned = str.stripTrailing();
                if (!cleaned.isEmpty()) {
                    appendSmart(cleaned);
                }
                if (cleaned.contains("completed") || cleaned.contains("error")) {
                    SwingUtilities.invokeLater(() -> {
                        healthButton.setEnabled(true);
                        setStatus("Connected", COLOR_OK);
                    });
                }
            }
        };
        client.streamHealth(groupId, proxy);
    }

    // opens the bidi alert session
    private void openAlertSession() {
        client.openAlertSession(buildAlertProxy());
        updateAlertSessionButtons();
        setStatus("Alert session open", COLOR_SERVER);
    }

    // sends one alert on the open stream
    private void sendAlert() {
        String deviceId = alertDeviceIdField.getText().trim();
        String issue = alertIssueField.getText().trim();
        if (deviceId.isEmpty()) {
            appendColoured("Please enter a Device ID.", COLOR_ERROR);
            return;
        }
        if (issue.isEmpty()) {
            appendColoured("Please enter an Issue.", COLOR_ERROR);
            return;
        }
        client.sendAlert(deviceId, issue, buildAlertProxy());
    }

    // closes the bidi alert session
    private void endAlertSession() {
        client.endAlertSession();
        updateAlertSessionButtons();
        appendColoured("Alert session ended by client.", COLOR_INFO);
        setStatus("Connected", COLOR_OK);
    }

    // routes bidi stream output to the coloured output pane
    private JTextArea buildAlertProxy() {
        return new JTextArea() {
            @Override
            public void append(String str) {
                String cleaned = str.stripTrailing();
                if (!cleaned.isEmpty()) {
                    appendSmart(cleaned);
                }
                SwingUtilities.invokeLater(() -> updateAlertSessionButtons());
            }
        };
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientGUI().setVisible(true));
    }
}
