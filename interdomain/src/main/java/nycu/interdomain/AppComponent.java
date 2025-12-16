/*
 * Copyright 2025-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//TODO IPV6 bgp speaker.
//TODO route implement.

package nycu.interdomain;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IPv6;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
// import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.MultiPointToSinglePointIntent;
import org.onosproject.net.intent.SinglePointToMultiPointIntent;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.PortNumber;
import org.onosproject.routeservice.ResolvedRoute;
import org.onosproject.routeservice.Route;
import org.onosproject.routeservice.RouteListener;
import org.onosproject.routeservice.RouteService;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;

import java.util.Set;

@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    // private final NameConfigListener cfgListener = new NameConfigListener();
    // private final ConfigFactory<ApplicationId, nycu.interdomain.Gateway> factory
    // =
    // new ConfigFactory<ApplicationId, nycu.interdomain.Gateway>(
    // APP_SUBJECT_FACTORY, nycu.interdomain.Gateway.class,
    // "UnicastDhcpConfig") {
    // @Override public nycu.interdomain.Gateway createConfig() {
    // return new nycu.interdomain.Gateway();
    // }
    // };

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected RouteService routeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    private ApplicationId appId;

    private final PacketProcessor processor = new BgpSpeaker();

    private HashMap<IpAddress, MacAddress> arpTable = new HashMap<>();

    private HashMap<IpAddress, MultiPointToSinglePointIntent> incomingBgp = new HashMap<>();
    private HashMap<IpAddress, SinglePointToMultiPointIntent> outgoingBgp = new HashMap<>();

    private HashMap<IpAddress, ConnectPoint> gateway = new HashMap<>();

    private final RouteListener routeListener = event -> {
        switch (event.type()) {
            case ROUTE_ADDED:
            case ROUTE_UPDATED:
                installRoute(event.subject());
                break;
            case ROUTE_REMOVED:
                withdrawRoute(event.subject());
                break;
            default:
                break;
        }
    };

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.interdomain");
        routeService.addListener(routeListener);
        packetService.addProcessor(processor, PacketProcessor.director(2));
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .build();
        packetService.requestPackets(selector, PacketPriority.REACTIVE, appId);

        TrafficSelector selector6 = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV6)
                .build();
        packetService.requestPackets(selector6, PacketPriority.REACTIVE, appId);

        log.info("Started");
        gateway.put(IpAddress.valueOf("192.168.63.1"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
        gateway.put(IpAddress.valueOf("192.168.70.23"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));

    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        routeService.removeListener(routeListener);
        packetService.removeProcessor(processor);
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .build();
        packetService.cancelPackets(selector, PacketPriority.REACTIVE, appId);
        log.info("Stopped");
    }

    private class BgpSpeaker implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            TCP tcpPkt = null;
            IpAddress srcIp = null;
            IpAddress dstIp = null;
            ConnectPoint cp = pkt.receivedFrom();
            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
                srcIp = IpAddress.valueOf(ipv4Packet.getSourceAddress());
                dstIp = IpAddress.valueOf(ipv4Packet.getDestinationAddress());
                if (ipv4Packet.getProtocol() == IPv4.PROTOCOL_TCP) {
                    tcpPkt = (TCP) ipv4Packet.getPayload();
                }
            } else if (ethPkt.getEtherType() == Ethernet.TYPE_IPV6) {
                IPv6 ipPacket = (IPv6) ethPkt.getPayload();
                srcIp = IpAddress.valueOf(IpAddress.Version.INET6, ipPacket.getSourceAddress());
                dstIp = IpAddress.valueOf(IpAddress.Version.INET6, ipPacket.getDestinationAddress());
                if (ipPacket.getNextHeader() == IPv6.PROTOCOL_TCP) {
                    tcpPkt = (TCP) ipPacket.getPayload();
                }
            } else {
                return;
            }

            if (tcpPkt != null && (tcpPkt.getSourcePort() == 179 || tcpPkt.getDestinationPort() == 179)) {
                // BGP packet
                log.info("Received BGP packet from {} to {}", srcIp, dstIp);
                // Process BGP packet here
                for (IpAddress gwIp : gateway.keySet()) {
                    if (dstIp.equals(gwIp)) {
                        arpTable.put(srcIp, ethPkt.getSourceMAC());
                        // Incoming BGP packet
                        log.info("Incoming BGP packet for gateway {}", gwIp);
                        // Build new intent combining existing ingress points (if any) with this CP
                        Set<FilteredConnectPoint> ingress = new HashSet<>();
                        MultiPointToSinglePointIntent existing = incomingBgp.get(gwIp);
                        if (existing != null && existing.filteredIngressPoints() != null) {
                            ingress.addAll(existing.filteredIngressPoints());
                        }
                        if (cp == null) {
                            log.warn("Packet has no ConnectPoint; skip intent update for {}", srcIp);
                            return;
                        }
                        ingress.add(new FilteredConnectPoint(cp, DefaultTrafficSelector.emptySelector()));

                        ConnectPoint gwConnectPoint = gateway.get(gwIp);
                        if (gwConnectPoint == null) {
                            log.warn("No gateway ConnectPoint configured for {}", gwIp);
                            return;
                        }
                        FilteredConnectPoint gwCp = new FilteredConnectPoint(gwConnectPoint,
                                                         DefaultTrafficSelector.emptySelector());

                        TrafficSelector selector = DefaultTrafficSelector.builder()
                                .matchEthType(Ethernet.TYPE_IPV4)
                                .matchIPDst(IpPrefix.valueOf(gwIp, 32))
                                .build();

                        MultiPointToSinglePointIntent intent = MultiPointToSinglePointIntent.builder()
                                .appId(appId)
                                .filteredIngressPoints(ingress)
                                .filteredEgressPoint(gwCp)
                                .selector(selector)
                                .build();

                        intentService.submit(intent);
                        incomingBgp.put(gwIp, intent);

                    } else if (srcIp.equals(gwIp)) {
                        // Outgoing BGP packet
                        log.info("Outgoing BGP packet from gateway {}", gwIp);

                        ConnectPoint gwConnectPoint = gateway.get(gwIp);
                        if (gwConnectPoint == null) {
                            log.warn("No gateway ConnectPoint configured for {}", gwIp);
                            return;
                        }
                        FilteredConnectPoint gwCp = new FilteredConnectPoint(gwConnectPoint,
                                                         DefaultTrafficSelector.emptySelector());
                        Set<FilteredConnectPoint> outPoint = new HashSet<>();
                        Set<Interface> outCp = interfaceService.getMatchingInterfaces(dstIp);
                        log.info(outCp.toString());
                        for (Interface intf : outCp) {
                            outPoint.add(new FilteredConnectPoint(intf.connectPoint(),
                                                         DefaultTrafficSelector.emptySelector()));
                        }
                        if (outPoint.isEmpty()) {
                            log.warn("No egress points found for dst {} from gateway {}", dstIp, gwIp);
                            return;
                        }

                        TrafficSelector selector = DefaultTrafficSelector.builder()
                                .matchEthType(Ethernet.TYPE_IPV4)
                                .matchIPSrc(IpPrefix.valueOf(gwIp, 32))
                                .build();

                        SinglePointToMultiPointIntent intent = SinglePointToMultiPointIntent.builder()
                            .appId(appId)
                            .filteredIngressPoint(gwCp)
                            .filteredEgressPoints(outPoint)
                            .selector(selector)
                            .build();
                        intentService.submit(intent);
                        outgoingBgp.put(gwIp, intent);

                    }
                }
            }

        }
    }

    private void installRoute(ResolvedRoute route) {
        IpPrefix dst = route.prefix();
        IpAddress nextHop = route.nextHop();
        log.info("Install route to {} via {}", dst, nextHop);

        Interface intf = interfaceService.getMatchingInterface(nextHop);
        if (intf == null) {
            log.warn("No interface for nextHop {}", nextHop);
            return;
        }
        MacAddress srcMac = intf.mac();
        MacAddress dstMac = arpTable.get(nextHop);
        if (dstMac == null) {
            log.warn("No MAC yet for {}", nextHop);
            return;
        }

        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(dst)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setEthSrc(srcMac)
                .setEthDst(dstMac)
                .setOutput(intf.connectPoint().port())
                .build();

        ForwardingObjective fwd = DefaultForwardingObjective.builder()
                .fromApp(appId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(40000)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .makePermanent()
                .add();

        flowObjectiveService.forward(intf.connectPoint().deviceId(), fwd);
    }

    private void withdrawRoute(ResolvedRoute route) {
        IpPrefix dst = route.prefix();
        IpAddress nextHop = route.nextHop();

        Interface intf = interfaceService.getMatchingInterface(nextHop);
        if (intf == null) {
            return;
        }

        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(dst)
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(intf.connectPoint().port())
                .build();

        ForwardingObjective fwd = DefaultForwardingObjective.builder()
                .fromApp(appId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(40000)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .remove();

        flowObjectiveService.forward(intf.connectPoint().deviceId(), fwd);
    }

}
// onos-create-app
// mvn clean install -DskipTests -Dcheckstyle.skip=true
// onos-app localhost install! ./target
// onos-app localhost uninstall nycu.

// sudo mn --controller=remote,127.0.0.1:6653 --topo=tree,depth=2
// --switch=ovs,protocols=OpenFlow14