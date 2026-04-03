/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package distsystem.iot.health.monitoring.grpc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

/**
 *
 * @author opeyemiadeniji
 */
public class ServiceDiscovery {

    private String requiredServiceType;
    private String requiredServiceName;
    private JmDNS jmdns;
    private int discoveredPort = -1;

    // initializes service discovery with the target service type and name
    public ServiceDiscovery(String inServiceType, String inServiceName) {
        requiredServiceType = inServiceType;
        requiredServiceName = inServiceName;
    }

    // discovers the required service and returns its port if found within the timeout
    public int discoverService(long timeoutMilliseconds) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        try {
            InetAddress bindAddress = getRealLocalAddress();
            System.out.println("Client: binding JmDNS to: " + bindAddress);

            jmdns = JmDNS.create(bindAddress);

            // add a listener to listen for service events and resolves the target service to retrieve its port
            jmdns.addServiceListener(requiredServiceType, new ServiceListener() {

                @Override
                public void serviceAdded(ServiceEvent event) {
                    System.out.println("Service added: " + event.getName());
                    jmdns.requestServiceInfo(event.getType(), event.getName(), 1000);
                }

                @Override
                public void serviceRemoved(ServiceEvent event) {
                    System.out.println("Service removed: " + event.getInfo());
                }

                @Override
                public void serviceResolved(ServiceEvent event) {
                    System.out.println("Service resolved: " + event.getInfo());
                    ServiceInfo info = event.getInfo();
                    String resolvedServiceName = info.getName();
                    System.out.println("Resolved service name: " + resolvedServiceName);
                    System.out.println("Resolved service port: " + info.getPort());

                    if (resolvedServiceName.contains(requiredServiceName)) {
                        discoveredPort = info.getPort();
                        System.out.println("Discovered " + resolvedServiceName
                                + " on port " + discoveredPort);
                        latch.countDown();
                    }
                }
            });

        } catch (UnknownHostException e) {
            System.out.println("UnknownHostException: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }

        latch.await(timeoutMilliseconds, TimeUnit.MILLISECONDS);
        System.out.println("discoverService returning port: " + discoveredPort);
        return discoveredPort;
    }

    // closes the JmDNS instance if it was created
    public void close() throws IOException {
        if (jmdns != null) {
            jmdns.close();
        }
    }

    // finds a usable local IPv4 address for binding JmDNS
    private InetAddress getRealLocalAddress() throws UnknownHostException {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                // ignore unusable network interfaces
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    // return the first valid IPv4 address found
                    if (!addr.isLoopbackAddress()
                            && addr.getHostAddress().contains(".")) {
                        return addr;
                    }
                }
            }
        } catch (SocketException e) {
            System.out.println("Could not enumerate network interfaces: " + e.getMessage());
        }

        return InetAddress.getLocalHost();
    }
}
