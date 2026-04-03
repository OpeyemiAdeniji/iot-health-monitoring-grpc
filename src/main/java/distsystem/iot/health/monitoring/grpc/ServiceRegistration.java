/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package distsystem.iot.health.monitoring.grpc;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 *
 * @author opeyemiadeniji
 */

public class ServiceRegistration {

    private static JmDNS jmdns;
    private static ServiceRegistration theRegister;

    // initializes JmDNS with a non-loopback local address
    private ServiceRegistration() throws IOException {
        InetAddress address = getLocalNonLoopbackAddress();
        jmdns = JmDNS.create(address);
        System.out.println("Registering on address: " + address);
    }

    // returns singleton instance, creating it if necessary
    public static ServiceRegistration getInstance() throws IOException {
        if (theRegister == null) {
            theRegister = new ServiceRegistration();
        }
        return theRegister;
    }

    // registers a service with JmDNS using the provided type, name, port and metadata
    public void registerService(String type, String name, int port, String text) throws IOException {
        ServiceInfo serviceInfo = ServiceInfo.create(type, name, port, text);
        jmdns.registerService(serviceInfo);
        System.out.println("Registered Service: " + serviceInfo);
    }

    // finds a non-loopback IPv4 address from available network interfaces
    private InetAddress getLocalNonLoopbackAddress() throws IOException {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address;
                    }
                }
            }
        } catch (SocketException e) {
            throw new IOException("Could not find non-loopback IPv4 address", e);
        }
        throw new IOException("No non-loopback IPv4 address found.");
    }
}