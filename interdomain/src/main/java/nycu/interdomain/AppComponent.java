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
// import org.onlab.packet.TpPort;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;
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

    private final ConfigFactory<ApplicationId, Gateway> factory = new ConfigFactory<ApplicationId, Gateway>(
            APP_SUBJECT_FACTORY, Gateway.class, "interdomain") {
        @Override
        public Gateway createConfig() {
            return new Gateway();
        }
    };

    private final NetworkConfigListener cfgListener = new InternalConfigListener();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgRegistry;

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

    private final PacketProcessor processor = new InterDomain();

    private ConsistentMap<IpAddress, HashMap<MacAddress, ConnectPoint>> arpConsistentMap;
    private Map<IpAddress, HashMap<MacAddress, ConnectPoint>> arpTable;

    private HashMap<IpAddress, MultiPointToSinglePointIntent> incomingBgp = new HashMap<>();
    private HashMap<IpAddress, List<PointToPointIntent>> outgoingBgp = new HashMap<>();

    private HashMap<IpPrefix, Pair<Interface, MacAddress>> routeTable = new HashMap<>();

    private Map<Pair<IpAddress, IpAddress>, Boolean> interPath = new HashMap<>();

    private List<Pair<TrafficSelector, PacketPriority>> packetRequests = new ArrayList<>();
    private List<IpAddress> peers = new ArrayList<>();

    private HashMap<IpAddress, ConnectPoint> gateway = new HashMap<>();

    // Configuration values loaded from config
    private IpPrefix intraPrefix4;
    private IpPrefix intraPrefix6;
    private MacAddress intraMac;
    private DeviceId blockDeviceId;

    private final RouteListener routeListener = event -> {
        log.info("Route event received: type={}, subject={}", event.type(), event.subject());
        switch (event.type()) {
            case ROUTE_ADDED:
            case ROUTE_UPDATED:
                log.info("Processing ROUTE_ADDED/UPDATED for: {}", event.subject());
                installRoute(event.subject());
                break;
            case ROUTE_REMOVED:
                log.info("Route removed: {}", event.subject());
                // withdrawRoute(event.subject());
                break;
            default:
                log.info("Unhandled route event type: {}", event.type());
                break;
        }
    };

    private void pushRule(DeviceId deviceId, TrafficSelector selector, TrafficTreatment treatment, int priority) {
        ForwardingObjective fwd = DefaultForwardingObjective.builder()
                .fromApp(appId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(priority)
                .withFlag(ForwardingObjective.Flag.SPECIFIC)
                .add();
        flowObjectiveService.forward(deviceId, fwd);
    }

    private void blockIngress() {
        if (blockDeviceId == null) {
            log.warn("Block device ID not configured, skipping blockIngress");
            return;
        }
        TrafficTreatment drop = DefaultTrafficTreatment.builder().drop().build();
        pushRule(blockDeviceId, DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_TCP)
                .build(), drop, 62000);
        pushRule(blockDeviceId, DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV6)
                .matchIPProtocol(IPv6.PROTOCOL_TCP)
                .build(), drop, 62000);
    }

    private void bgpspeaker() {
        log.info("Setting up BGP speaker intents");
        for (IpAddress gip : gateway.keySet()) {
            ConnectPoint gwCp = gateway.get(gip);
            if (gwCp == null) {
                log.warn("Gateway connect point is null for {}, skipping", gip);
                continue;
            }

            Set<Interface> intfs = interfaceService.getMatchingInterfaces(gip);
            if (intfs == null || intfs.isEmpty()) {
                log.warn("No matching interfaces found for gateway {}, skipping", gip);
                continue;
            }
            List<IpPrefix> peerPrefixes = new ArrayList<>();
            Set<FilteredConnectPoint> fcps = new HashSet<>();
            for (Interface intf : intfs) {
                if (intf.connectPoint() != null) {
                    fcps.add(new FilteredConnectPoint(intf.connectPoint()));
                    log.info("Interface for gateway {}: {}", gip, intf.name());
                }
            }

            if (fcps.isEmpty()) {
                log.warn("No valid connect points found for gateway {}, skipping intent creation", gip);
                continue;
            }

            TrafficSelector forward;
            TrafficSelector rev;
            if (gip.isIp4()) {
                forward = DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(gip.toIpPrefix())
                        .build();
                rev = DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPDst(gip.toIpPrefix())
                        .build();
            } else {
                forward = DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(gip.toIpPrefix())
                        .build();
                rev = DefaultTrafficSelector.builder()
                        .matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Dst(gip.toIpPrefix())
                        .build();
            }

            try {
                SinglePointToMultiPointIntent intent = SinglePointToMultiPointIntent.builder()
                        .appId(appId)
                        .selector(forward)
                        .filteredIngressPoint(new FilteredConnectPoint(gwCp))
                        .filteredEgressPoints(fcps)
                        .priority(65000)
                        .build();
                PointToPointIntent reverse1 = PointToPointIntent.builder()
                        .appId(appId)
                        .selector(rev)
                        .filteredIngressPoint(new FilteredConnectPoint(gwCp))
                        .filteredEgressPoint(fcps.iterator().next()) // For simplicity, using one egress point
                        .priority(65000)
                        .build();
                log.info("Submitting intents for gateway {}", gip);
                log.info("SP2MP intent: ingress={}, egress count={}", gwCp, fcps.size());
                log.info("MP2SP intent: ingress count={}, egress={}", fcps.size(), gwCp);
                intentService.submit(intent);
                // intentService.submit(reverse);
            } catch (Exception e) {
                log.error("Failed to create/submit intents for gateway {}: {}", gip, e.getMessage());
            }
        }
    }

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.interdomain");

        // Register config factory
        cfgRegistry.registerConfigFactory(factory);
        cfgRegistry.addListener(cfgListener);

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

        loadConfig();

        log.info("Started");
    }

    private void loadConfig() {
        Gateway config = cfgRegistry.getConfig(appId, Gateway.class);
        if (config == null) {
            log.warn("No configuration found, using defaults");
            // Set defaults
            intraPrefix4 = IpPrefix.valueOf("172.16.23.0/24");
            intraPrefix6 = IpPrefix.valueOf("2a0b:4e07:c4:23::/64");
            intraMac = MacAddress.valueOf("00:00:23:00:00:06");
            blockDeviceId = DeviceId.deviceId("of:0000d2f4d1307942");
            // Default gateways
            gateway.put(IpAddress.valueOf("192.168.63.1"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
            gateway.put(IpAddress.valueOf("192.168.70.23"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));
            gateway.put(IpAddress.valueOf("fd63::1"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
            gateway.put(IpAddress.valueOf("fd70::23"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));
            // Default peers
            peers.add(IpAddress.valueOf("192.168.70.253"));
            peers.add(IpAddress.valueOf("fd70::fe"));
            peers.add(IpAddress.valueOf("192.168.70.22"));
            peers.add(IpAddress.valueOf("fd70::22"));
            peers.add(IpAddress.valueOf("192.168.70.24"));
            peers.add(IpAddress.valueOf("fd70::24"));
            peers.add(IpAddress.valueOf("192.168.63.2"));
            peers.add(IpAddress.valueOf("fd63::2"));
        } else {
            log.info("Loading configuration from network config");
            intraPrefix4 = config.intraPrefix4();
            intraPrefix6 = config.intraPrefix6();
            intraMac = config.intraMac();
            blockDeviceId = config.blockDeviceId();
            gateway.clear();
            gateway.putAll(config.gateways());
            peers.clear();
            peers.addAll(config.peers());
        }

        log.info("Config loaded: intraPrefix4={}, intraPrefix6={}, blockDeviceId={}",
                intraPrefix4, intraPrefix6, blockDeviceId);
        log.info("Gateways: {}", gateway);
        log.info("Peers: {}", peers);

        // Register packet requests with loaded config
        TrafficSelector selector3 = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(intraPrefix4)
                .build();
        registerPacketRequest(selector3, PacketPriority.HIGH);

        TrafficSelector selector4 = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV6)
                .matchIPv6Dst(intraPrefix6)
                .build();
        registerPacketRequest(selector4, PacketPriority.HIGH);

        blockIngress();
        bgpspeaker();
    }

    private class InternalConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if (event.configClass() == Gateway.class) {
                switch (event.type()) {
                    case CONFIG_ADDED:
                    case CONFIG_UPDATED:
                        log.info("Configuration updated, reloading...");
                        loadConfig();
                        break;
                    default:
                        break;
                }
            }
        }
    }

    @Deactivate
    protected void deactivate() {
        cfgRegistry.removeListener(cfgListener);
        cfgRegistry.unregisterConfigFactory(factory);
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

        // Purge flow objectives for device used in blockIngress()
        if (blockDeviceId != null) {
            flowObjectiveService.purgeAll(blockDeviceId, appId);
        }

        // Purge flow objectives for all devices used in installFlowPath()
        for (ConnectPoint cp : gateway.values()) {
            flowObjectiveService.purgeAll(cp.deviceId(), appId);
        }

        log.info("Stopped");
    }

    private void registerPacketRequest(TrafficSelector selector, PacketPriority priority) {
        Pair<TrafficSelector, PacketPriority> entry = Pair.of(selector, priority);
        if (!packetRequests.contains(entry)) {
            packetRequests.add(entry);
        }
        packetService.requestPackets(selector, priority, appId);
    }

    private class InterDomain implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            IpAddress srcIp = null;
            IpAddress dstIp = null;
            ConnectPoint cp = pkt.receivedFrom();
            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
                srcIp = IpAddress.valueOf(ipv4Packet.getSourceAddress());
                dstIp = IpAddress.valueOf(ipv4Packet.getDestinationAddress());
            } else if (ethPkt.getEtherType() == Ethernet.TYPE_IPV6) {
                IPv6 ipPacket = (IPv6) ethPkt.getPayload();
                srcIp = IpAddress.valueOf(IpAddress.Version.INET6,
                        ipPacket.getSourceAddress());
                dstIp = IpAddress.valueOf(IpAddress.Version.INET6,
                        ipPacket.getDestinationAddress());
            } else {
                return;
            }
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

        IpPrefix intraPrefix = isIpv6 ? intraPrefix6 : intraPrefix4;
        String ipVersion = isIpv6 ? "IPv6" : "IPv4";

        IpPrefix srcPrefix = srcIp.toIpPrefix();
        IpPrefix dstPrefix = dstIp.toIpPrefix();
        boolean srcIsIntra = intraPrefix.contains(srcPrefix);
        boolean dstIsIntra = intraPrefix.contains(dstPrefix);

        if (srcIsIntra && dstIsIntra) {
            // log.info("Ignore intradomain {} packet from {} to {}", ipVersion, srcIp, dstIp);
            return;
        }

        TrafficSelector selectorInOut;
        TrafficSelector selectorOutIn;
        TrafficTreatment treatmentInOut;
        TrafficTreatment treatmentOutIn;
        ConnectPoint forwardCp;
        // print arp table
        for (IpAddress ip : arpTable.keySet()) {
            HashMap<MacAddress, ConnectPoint> entryMap = arpTable.get(ip);
            for (MacAddress mac : entryMap.keySet()) {
                ConnectPoint acp = entryMap.get(mac);
                log.info("ARP Table Entry - IP: {}, MAC: {}, ConnectPoint: {}",
                        ip.toString(), mac.toString(), acp.toString());
            }
        }
        if (srcIsIntra && !dstIsIntra) {
            // Inner to outer
            log.info("Interdomain {} packet from {} to outer {}", ipVersion, srcIp, dstIp);
            Pair<IpPrefix, Pair<Interface, MacAddress>> lpmResult = longestPrefixMatch(dstIp);
            if (lpmResult == null) {
                log.warn("No {} route for outer destination {} (LPM failed)", ipVersion, dstIp);
                return;
            }
            Pair<Interface, MacAddress> entry = lpmResult.getRight();
            // print route entry
            log.info("Route entry for outer destination {}: matched prefix={}, Interface={}, NextHopMac={}",
                    dstIp, lpmResult.getLeft(), entry.getLeft().name(), entry.getRight());

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

            installPathIfAbsent(srcIp, dstIp, cp, forwardCp, selectorInOut, treatmentInOut, 64000);

        } else if (!srcIsIntra && dstIsIntra) {
            // Outer to inner
            log.info("Interdomain {} packet from outer {} to {}", ipVersion, srcIp, dstIp);
            HashMap<MacAddress, ConnectPoint> entryMap = arpTable.get(dstIp);
            if (entryMap == null || entryMap.isEmpty()) {
                log.warn("No ARP/ND entry for inner host {}", dstIp);
                return;
            }

            MacAddress hostMac = findNearestMac(entryMap, cp, true);
            ConnectPoint hostCp = entryMap.get(hostMac);

            Pair<IpPrefix, Pair<Interface, MacAddress>> lpmResult = longestPrefixMatch(srcIp);
            if (lpmResult == null) {
                log.warn("No {} route entry for outer source {} (LPM failed)", ipVersion, srcIp);
                return;
            }
            // Route exists - we have a path back to the source
            log.info("Route entry for outer source {}: matched prefix={}, Interface={}, NextHopMac={}",
                    srcIp, lpmResult.getLeft(), lpmResult.getRight().getLeft().name(),
                    lpmResult.getRight().getRight());
            treatmentOutIn = DefaultTrafficTreatment.builder()
                    .setEthSrc(intraMac)
                    .setEthDst(hostMac)
                    .build();
            selectorOutIn = buildSelector(isIpv6, srcPrefix, dstPrefix);

            installPathIfAbsent(srcIp, dstIp, cp, hostCp, selectorOutIn, treatmentOutIn, 64000);

        } else {
            // Transit mode (outer to outer)
            log.info("Interdomain {} transit packet from outer {} to outer {}", ipVersion, srcIp, dstIp);
            Pair<IpPrefix, Pair<Interface, MacAddress>> lpmResult = longestPrefixMatch(dstIp);
            if (lpmResult == null) {
                log.warn("No {} route entry for transit dst {} (LPM failed)", ipVersion, dstIp);
                return;
            }
            Pair<Interface, MacAddress> entry = lpmResult.getRight();

            Interface intf = entry.getLeft();
            ConnectPoint outCp = intf.connectPoint();
            MacAddress nextHopMac = entry.getRight();
            MacAddress routerMac = intf.mac();
            log.info("Route entry for transit dst {}: matched prefix={}, Interface={}, NextHopMac={}",
                    dstIp, lpmResult.getLeft(), intf.name(), nextHopMac);
            treatmentInOut = DefaultTrafficTreatment.builder()
                    .setEthSrc(routerMac)
                    .setEthDst(nextHopMac)
                    .build();
            selectorInOut = buildSelector(isIpv6, srcPrefix, dstPrefix);

            installPathIfAbsent(srcIp, dstIp, cp, outCp, selectorInOut, treatmentInOut, 64000);
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

    /**
     * Perform Longest Prefix Match (LPM) lookup in the route table.
     * Returns the route entry with the longest matching prefix for the given IP.
     */
    private Pair<IpPrefix, Pair<Interface, MacAddress>> longestPrefixMatch(IpAddress ip) {
        Pair<IpPrefix, Pair<Interface, MacAddress>> bestMatch = null;
        int longestPrefixLen = -1;

        for (Map.Entry<IpPrefix, Pair<Interface, MacAddress>> entry : routeTable.entrySet()) {
            IpPrefix prefix = entry.getKey();
            // Check if the prefix contains the IP and has the same IP version
            if (prefix.contains(ip) && prefix.prefixLength() > longestPrefixLen) {
                longestPrefixLen = prefix.prefixLength();
                bestMatch = Pair.of(prefix, entry.getValue());
            }
        }

        if (bestMatch != null) {
            log.debug("LPM for {}: matched prefix {}", ip, bestMatch.getLeft());
        } else {
            log.debug("LPM for {}: no match found", ip);
        }

        return bestMatch;
    }

    private void installRoute(ResolvedRoute route) {
        IpPrefix dst = route.prefix();
        IpAddress nextHop = route.nextHop();
        log.info("Insert route to {} via {} into routing table", dst, nextHop);

        // Tested : print ip, mac, and cp in arp table.
        // for (IpAddress ip : arpTable.keySet()) {
        //     HashMap<MacAddress, ConnectPoint> entry = arpTable.get(ip);
        //     for (MacAddress mac : entry.keySet()) {
        //         ConnectPoint cp = entry.get(mac);
        //         log.info("ARP Entry - IP: {}, MAC: {}, CP: {}", ip.toString(),
        //                 mac.toString(), cp.toString());
        //     }
        // }

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
            registerPacketRequest(selector, PacketPriority.HIGH3);
        } else {
            log.info("Installed IPv6 route to {} via {} on intf {} with next hop MAC {}",
                    dst.toString(), nextHop.toString(), intf.name(), dstMac.toString());
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV6)
                    .matchIPv6Dst(dst)
                    .build();
            registerPacketRequest(selector, PacketPriority.HIGH3);
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

    private MacAddress findNearestMac(HashMap<MacAddress, ConnectPoint> entries,
            ConnectPoint sender, boolean enableLogging) {
        if (entries.size() == 1) {
            return entries.keySet().iterator().next();
        }

        MacAddress nearestMac = null;
        int minDist = Integer.MAX_VALUE;
        for (MacAddress mac : entries.keySet()) {
            ConnectPoint cp = entries.get(mac);
            if (enableLogging) {
                log.info("Compare sender " + sender + " with " + cp);
            }
            int dist = near(sender, cp);
            if (dist < minDist) {
                minDist = dist;
                nearestMac = mac;
                if (enableLogging) {
                    log.info("Update to target MAC " + nearestMac + " with distance " + minDist);
                }
            }
        }
        return nearestMac;
    }

    private int near(ConnectPoint a, ConnectPoint b) {
        // calculate the distance between two connect points
        if (a.deviceId().equals(b.deviceId())) {
            return 0;
        } else {
            Topology topology = topologyService.currentTopology();
            Iterable<Path> paths = topologyService.getPaths(topology, a.deviceId(), b.deviceId());
            int minHops = Integer.MAX_VALUE;
            for (Path p : paths) {
                if (minHops > p.links().size()) {
                    minHops = p.links().size();
                }
            }
            return minHops;
        }
    }
}
// onos-create-app
// mvn clean install -DskipTests -Dcheckstyle.skip=true
// onos-app localhost install! ./target
// onos-app localhost uninstall nycu.

// sudo mn --controller=remote,127.0.0.1:6653 --topo=tree,depth=2
// --switch=ovs,protocols=OpenFlow14