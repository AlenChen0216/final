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

import org.apache.commons.lang3.tuple.Pair;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IPv6;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip6Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
// import org.onlab.packet.TpPort;
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
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.PortNumber;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.Path;
import org.onosproject.net.Link;
import org.onosproject.routeservice.ResolvedRoute;
// import org.onosproject.routeservice.Route;
import org.onosproject.routeservice.RouteListener;
import org.onosproject.routeservice.RouteService;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.onlab.util.KryoNamespace;

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

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected StorageService storageService;

    private ApplicationId appId;

    private final PacketProcessor processor = new BgpSpeaker();

    private ConsistentMap<IpAddress, HashMap<MacAddress, ConnectPoint>> arpConsistentMap;
    private Map<IpAddress, HashMap<MacAddress, ConnectPoint>> arpTable;

    private HashMap<IpAddress, MultiPointToSinglePointIntent> incomingBgp = new HashMap<>();
    private HashMap<IpAddress, List<PointToPointIntent>> outgoingBgp = new HashMap<>();

    private HashMap<IpPrefix, Pair<Interface, MacAddress>> routeTable = new HashMap<>();

    private Map<Pair<IpAddress, IpAddress>, Boolean> interPath = new HashMap<>();

    private List<Pair<TrafficSelector, PacketPriority>> packetRequests = new ArrayList<>();

    private HashMap<IpAddress, ConnectPoint> gateway = new HashMap<>();
    private static final IpPrefix INTRAPREFIX4 = IpPrefix.valueOf("172.16.23.0/24");
    private static final IpPrefix INTRAPREFIX6 = IpPrefix.valueOf("2a0b:4e07:c4:23::/64");
    private static final MacAddress INTRAMAC = MacAddress.valueOf("00:00:23:00:00:06");

    private final RouteListener routeListener = event -> {
        switch (event.type()) {
            case ROUTE_ADDED:
            case ROUTE_UPDATED:
                installRoute(event.subject());
                break;
            case ROUTE_REMOVED:
                // withdrawRoute(event.subject());
                break;
            default:
                break;
        }
    };

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.interdomain");
        routeService.addListener(routeListener);
        KryoNamespace kryo = new KryoNamespace.Builder()
                .register(IpAddress.class, IpAddress.Version.class,
                        Ip4Address.class, Ip6Address.class,
                        MacAddress.class, byte[].class, HashMap.class,
                        ConnectPoint.class, DeviceId.class, PortNumber.class, URI.class)
                .build();
        arpConsistentMap = storageService.<IpAddress, HashMap<MacAddress, ConnectPoint>>consistentMapBuilder()
                .withName("proxyndp-arp")
                .withSerializer(Serializer.using(kryo))
                .build();

        arpTable = arpConsistentMap.asJavaMap();

        packetService.addProcessor(processor, PacketProcessor.director(2));

        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_TCP)
                .build();
        registerPacketRequest(selector, PacketPriority.HIGH);

        TrafficSelector selector2 = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV6)
                .matchIPProtocol(IPv6.PROTOCOL_TCP)
                .build();
        registerPacketRequest(selector2, PacketPriority.HIGH);

        TrafficSelector selector3 = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(INTRAPREFIX4)
                .build();
        registerPacketRequest(selector3, PacketPriority.HIGH1);

        TrafficSelector selector4 = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV6)
                .matchIPv6Dst(INTRAPREFIX6)
                .build();
        registerPacketRequest(selector4, PacketPriority.HIGH1);
        log.info("Started");
        gateway.put(IpAddress.valueOf("192.168.63.1"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
        gateway.put(IpAddress.valueOf("192.168.70.23"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));
        gateway.put(IpAddress.valueOf("fd63::1"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
        gateway.put(IpAddress.valueOf("fd70::23"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        routeService.removeListener(routeListener);

        for (MultiPointToSinglePointIntent intent : incomingBgp.values()) {
            intentService.withdraw(intent);
        }
        for (List<PointToPointIntent> intents : outgoingBgp.values()) {
            for (PointToPointIntent intent : intents) {
                intentService.withdraw(intent);
            }
        }
        incomingBgp.clear();
        outgoingBgp.clear();
        interPath.clear();

        for (Pair<TrafficSelector, PacketPriority> request : packetRequests) {
            packetService.cancelPackets(request.getLeft(), request.getRight(), appId);
        }
        packetRequests.clear();

        log.info("Stopped");
    }

    private void registerPacketRequest(TrafficSelector selector, PacketPriority priority) {
        Pair<TrafficSelector, PacketPriority> entry = Pair.of(selector, priority);
        if (!packetRequests.contains(entry)) {
            packetRequests.add(entry);
        }
        packetService.requestPackets(selector, priority, appId);
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
            }

            // ===== For BGP Speaker =======
            if (tcpPkt != null && (tcpPkt.getSourcePort() == 179 || tcpPkt.getDestinationPort() == 179)) {
                // BGP packet
                log.info("Received BGP packet from {} to {}", srcIp, dstIp);
                // Process BGP packet here
                for (IpAddress gwIp : gateway.keySet()) {
                    if (dstIp.equals(gwIp)) {
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
                        Set<Interface> incp = interfaceService.getMatchingInterfaces(srcIp);
                        boolean matched = false;
                        for (Interface intf : incp) {
                            if (intf.connectPoint().equals(cp)) {
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            log.warn("Ingress ConnectPoint {} does not match any interface for {}", cp, srcIp);
                            return;
                        }
                        ingress.add(new FilteredConnectPoint(cp, DefaultTrafficSelector.emptySelector()));

                        ConnectPoint gwConnectPoint = gateway.get(gwIp);
                        if (gwConnectPoint == null) {
                            log.warn("No gateway ConnectPoint configured for {}", gwIp);
                            return;
                        }
                        for (FilteredConnectPoint fcp : ingress) {
                            log.info("Ingress point: {}", fcp.connectPoint());
                        }
                        FilteredConnectPoint gwCp = new FilteredConnectPoint(gwConnectPoint,
                                DefaultTrafficSelector.emptySelector());

                        TrafficSelector selector = null;

                        if (gwIp.isIp4()) {
                            selector = DefaultTrafficSelector.builder()
                                    .matchEthType(Ethernet.TYPE_IPV4)
                                    .matchIPDst(IpPrefix.valueOf(gwIp, 32))
                                    .build();
                        } else {
                            selector = DefaultTrafficSelector.builder()
                                    .matchEthType(Ethernet.TYPE_IPV6)
                                    .matchIPv6Dst(IpPrefix.valueOf(gwIp, 64))
                                    .build();
                        }

                        MultiPointToSinglePointIntent intent = null;

                        if (existing != null) {
                            intent = MultiPointToSinglePointIntent.builder()
                                    .key(existing.key())
                                    .appId(appId)
                                    .filteredIngressPoints(ingress)
                                    .filteredEgressPoint(gwCp)
                                    .selector(selector)
                                    .priority(63000)
                                    .build();
                        } else {
                            intent = MultiPointToSinglePointIntent.builder()
                                    .appId(appId)
                                    .filteredIngressPoints(ingress)
                                    .filteredEgressPoint(gwCp)
                                    .selector(selector)
                                    .priority(63000)
                                    .build();
                        }
                        log.info("Submitting intent for incoming BGP to gateway {}: {}", gwIp, intent);
                        intentService.submit(intent);
                        incomingBgp.put(gwIp, intent);

                    } else if (srcIp.equals(gwIp) && outgoingBgp.get(gwIp) == null) {
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
                        for (Interface intf : outCp) {
                            outPoint.add(new FilteredConnectPoint(intf.connectPoint(),
                                    DefaultTrafficSelector.emptySelector()));
                        }
                        if (outPoint.isEmpty()) {
                            log.warn("No egress points found for dst {} from gateway {}", dstIp, gwIp);
                            return;
                        }

                        TrafficSelector selector = null;

                        if (gwIp.isIp4()) {
                            selector = DefaultTrafficSelector.builder()
                                    .matchEthType(Ethernet.TYPE_IPV4)
                                    .matchIPSrc(IpPrefix.valueOf(gwIp, 32))
                                    .build();
                        } else {
                            selector = DefaultTrafficSelector.builder()
                                    .matchEthType(Ethernet.TYPE_IPV6)
                                    .matchIPv6Src(IpPrefix.valueOf(gwIp, 64))
                                    .build();
                        }
                        List<PointToPointIntent> intentList = new ArrayList<>();
                        for (FilteredConnectPoint ocp : outPoint) {
                            PointToPointIntent intent = PointToPointIntent.builder()
                                    .appId(appId)
                                    .filteredIngressPoint(gwCp)
                                    .filteredEgressPoint(ocp)
                                    .selector(selector)
                                    .priority(63000)
                                    .build();
                            log.info("Submitting intent for outgoing BGP from gateway {}: {}", gwIp, intent);
                            intentService.submit(intent);
                            intentList.add(intent);
                        }
                        outgoingBgp.put(gwIp, intentList);
                    }
                }
                return;
            }
            // ======================================
            interdomainProcess(ethPkt, srcIp, dstIp, cp, context);
        }
    }

    private void interdomainProcess(Ethernet ethPkt, IpAddress srcIp, IpAddress dstIp, ConnectPoint cp,
            PacketContext context) {
        // ===== For interdomain traffic =======
        boolean isIpv6 = ethPkt.getEtherType() == Ethernet.TYPE_IPV6;
        if (ethPkt.getEtherType() != Ethernet.TYPE_IPV4 && !isIpv6) {
            return;
        }

        IpPrefix intraPrefix = isIpv6 ? INTRAPREFIX6 : INTRAPREFIX4;
        int prefixLen = isIpv6 ? 64 : 24;
        String ipVersion = isIpv6 ? "IPv6" : "IPv4";

        IpPrefix srcPrefix = srcIp.toIpPrefix();
        IpPrefix dstPrefix = dstIp.toIpPrefix();
        boolean srcIsIntra = intraPrefix.contains(srcPrefix);
        boolean dstIsIntra = intraPrefix.contains(dstPrefix);

        if (srcIsIntra && dstIsIntra) {
            log.info("Ignore intradomain {} packet from {} to {}", ipVersion, srcIp, dstIp);
            return;
        }

        TrafficSelector selectorInOut;
        TrafficSelector selectorOutIn;
        TrafficTreatment treatmentInOut;
        TrafficTreatment treatmentOutIn;
        ConnectPoint forwardCp;

        if (srcIsIntra && !dstIsIntra) {
            // Inner to outer
            log.info("Interdomain {} packet from {} to outer {}", ipVersion, srcIp, dstIp);
            Pair<Interface, MacAddress> entry = routeTable.get(IpPrefix.valueOf(dstIp, prefixLen));
            if (entry == null) {
                log.warn("No {} route for outer destination {}", ipVersion, dstIp);
                return;
            }

            Interface intf = entry.getLeft();
            ConnectPoint outCp = intf.connectPoint();
            MacAddress nextHopMac = entry.getRight();
            MacAddress routerMac = intf.mac();

            treatmentInOut = DefaultTrafficTreatment.builder()
                    .setEthSrc(routerMac)
                    .setEthDst(nextHopMac)
                    .build();
            selectorInOut = buildSelector(isIpv6, srcPrefix, dstPrefix);

            forwardCp = outCp;

            installPathIfAbsent(srcIp, dstIp, cp, forwardCp, selectorInOut, treatmentInOut, 63000);

        } else if (!srcIsIntra && dstIsIntra) {
            // Outer to inner
            log.info("Interdomain {} packet from outer {} to {}", ipVersion, srcIp, dstIp);
            HashMap<MacAddress, ConnectPoint> entryMap = arpTable.get(dstIp);
            if (entryMap == null || entryMap.isEmpty()) {
                log.warn("No ARP/ND entry for inner host {}", dstIp);
                return;
            }
            // TODO for anycast, look up if the host is near the incoming cp.
            MacAddress hostMac = entryMap.keySet().iterator().next();
            ConnectPoint hostCp = entryMap.get(hostMac);

            Pair<Interface, MacAddress> routeEntry = routeTable.get(IpPrefix.valueOf(srcIp, prefixLen));
            if (routeEntry == null) {
                log.warn("No {} route entry for outer source {}", ipVersion, srcIp);
                return;
            }

            treatmentOutIn = DefaultTrafficTreatment.builder()
                    .setEthSrc(INTRAMAC)
                    .setEthDst(hostMac)
                    .build();
            selectorOutIn = buildSelector(isIpv6, srcPrefix, dstPrefix);

            installPathIfAbsent(srcIp, dstIp, cp, hostCp, selectorOutIn, treatmentOutIn, 63000);

        } else {
            // Transit mode (outer to outer)
            log.info("Interdomain {} transit packet from outer {} to outer {}", ipVersion, srcIp, dstIp);
            Pair<Interface, MacAddress> entry = routeTable.get(IpPrefix.valueOf(dstIp, prefixLen));
            if (entry == null) {
                log.warn("No {} route entry for transit dst {}", ipVersion, dstIp);
                return;
            }

            Interface intf = entry.getLeft();
            ConnectPoint outCp = intf.connectPoint();
            MacAddress nextHopMac = entry.getRight();
            MacAddress routerMac = intf.mac();

            treatmentInOut = DefaultTrafficTreatment.builder()
                    .setEthSrc(routerMac)
                    .setEthDst(nextHopMac)
                    .build();
            selectorInOut = buildSelector(isIpv6, srcPrefix, dstPrefix);

            installPathIfAbsent(srcIp, dstIp, cp, outCp, selectorInOut, treatmentInOut, 63000);
        }

        context.block();
    }

    private TrafficSelector buildSelector(boolean isIpv6, IpPrefix srcPrefix, IpPrefix dstPrefix) {
        if (isIpv6) {
            return DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV6)
                    .matchIPv6Src(srcPrefix)
                    .matchIPv6Dst(dstPrefix)
                    .build();
        } else {
            return DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV4)
                    .matchIPSrc(srcPrefix)
                    .matchIPDst(dstPrefix)
                    .build();
        }
    }

    private void installRoute(ResolvedRoute route) {
        IpPrefix dst = route.prefix();
        IpAddress nextHop = route.nextHop();
        log.info("Insert route to {} via {} into routing tableRRR", dst, nextHop);

        // Tested : print ip, mac, and cp in arp table.
        for (IpAddress ip : arpTable.keySet()) {
            HashMap<MacAddress, ConnectPoint> entry = arpTable.get(ip);
            for (MacAddress mac : entry.keySet()) {
                ConnectPoint cp = entry.get(mac);
                log.info("ARP Entry - IP: {}, MAC: {}, CP: {}", ip.toString(),
                        mac.toString(), cp.toString());
            }
        }

        Interface intf = interfaceService.getMatchingInterface(nextHop);
        if (intf == null) {
            log.warn("No interface for nextHop {}", nextHop);
            return;
        }
        // MacAddress srcMac = intf.mac();
        HashMap<MacAddress, ConnectPoint> entry = arpTable.get(nextHop);

        if (entry == null || entry.isEmpty()) {
            log.warn("No MAC yet for {}", nextHop);
            return;
        }
        MacAddress dstMac = null;

        if (entry.size() > 1) {
            // TODO
        } else {
            dstMac = entry.keySet().iterator().next();
        }
        routeTable.put(dst, Pair.of(intf, dstMac));

        if (nextHop.isIp4()) {
            log.info("Installed IPv4 route to {} via {} on intf {} with next hop MAC {}",
                    dst.toString(), nextHop.toString(), intf.name(), dstMac.toString());
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV4)
                    .matchIPDst(dst)
                    .build();
            registerPacketRequest(selector, PacketPriority.HIGH1);
        } else {
            log.info("Installed IPv6 route to {} via {} on intf {} with next hop MAC {}",
                    dst.toString(), nextHop.toString(), intf.name(), dstMac.toString());
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV6)
                    .matchIPv6Dst(dst)
                    .build();
            registerPacketRequest(selector, PacketPriority.HIGH1);
        }
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

    private void installPathIfAbsent(IpAddress srcIp, IpAddress dstIp, ConnectPoint srcCp, ConnectPoint dstCp,
            TrafficSelector selector, TrafficTreatment baseTreatment, int priority) {
        Pair<IpAddress, IpAddress> key = Pair.of(srcIp, dstIp);
        if (Boolean.TRUE.equals(interPath.get(key))) {
            return;
        }
        installFlowPath(srcCp, dstCp, selector, baseTreatment, priority);
        interPath.put(key, true);
    }

    private void installFlowPath(ConnectPoint srcCp, ConnectPoint dstCp, TrafficSelector selector,
            TrafficTreatment baseTreatment, int priority) {
        if (srcCp == null || dstCp == null) {
            log.warn("Cannot install path with null endpoints: {} -> {}", srcCp, dstCp);
            return;
        }

        Topology topology = topologyService.currentTopology();

        if (srcCp.deviceId().equals(dstCp.deviceId())) {
            TrafficTreatment treatment = DefaultTrafficTreatment.builder(baseTreatment)
                    .setOutput(dstCp.port())
                    .build();
            TrafficSelector sel = DefaultTrafficSelector.builder(selector)
                    .matchInPort(srcCp.port())
                    .build();
            applyFlow(srcCp.deviceId(), sel, treatment, priority);
            return;
        }

        Set<Path> paths = topologyService.getPaths(topology, srcCp.deviceId(), dstCp.deviceId());
        Path path = paths.stream().findFirst().orElse(null);
        if (path == null) {
            log.warn("No available path between {} and {}", srcCp.deviceId(), dstCp.deviceId());
            return;
        }

        List<Link> links = path.links();
        if (links.isEmpty()) {
            log.warn("Empty path between {} and {}", srcCp.deviceId(), dstCp.deviceId());
            return;
        }

        // First hop: from ingress CP toward first link
        Link firstLink = links.get(0);
        TrafficTreatment firstTreatment = DefaultTrafficTreatment.builder(baseTreatment)
                .setOutput(firstLink.src().port())
                .build();
        TrafficSelector firstSelector = DefaultTrafficSelector.builder(selector)
                .matchInPort(srcCp.port())
                .build();
        applyFlow(srcCp.deviceId(), firstSelector, firstTreatment, priority);

        // Intermediate hops
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i);
            // Skip first link handled above
            if (i == 0) {
                continue;
            }
            TrafficTreatment hopTreatment = DefaultTrafficTreatment.builder(baseTreatment)
                    .setOutput(link.src().port())
                    .build();
            TrafficSelector hopSelector = DefaultTrafficSelector.builder(selector)
                    .build();
            applyFlow(link.src().deviceId(), hopSelector, hopTreatment, priority);
        }

        // Egress hop on destination device
        TrafficTreatment lastTreatment = DefaultTrafficTreatment.builder(baseTreatment)
                .setOutput(dstCp.port())
                .build();
        TrafficSelector lastSelector = DefaultTrafficSelector.builder(selector)
                .build();
        applyFlow(dstCp.deviceId(), lastSelector, lastTreatment, priority);
    }

    private void applyFlow(DeviceId deviceId, TrafficSelector selector, TrafficTreatment treatment, int priority) {
        ForwardingObjective fwd = DefaultForwardingObjective.builder()
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(priority)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .fromApp(appId)
                .makePermanent()
                .add();
        flowObjectiveService.forward(deviceId, fwd);
    }

    private boolean near(ConnectPoint a, ConnectPoint b) {
        String devA = a.deviceId().toString();
        String devB = b.deviceId().toString();
        String[] partsA = devA.split(":");
        String[] partsB = devB.split(":");
        long idA = Long.parseLong(partsA[1], 16);
        long idB = Long.parseLong(partsB[1], 16);
        long dist = Math.abs(idA - idB);
        return (dist == 0);
    }
}
// onos-create-app
// mvn clean install -DskipTests -Dcheckstyle.skip=true
// onos-app localhost install! ./target
// onos-app localhost uninstall nycu.

// sudo mn --controller=remote,127.0.0.1:6653 --topo=tree,depth=2
// --switch=ovs,protocols=OpenFlow14