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
package nycu.winlab.proxyndp;

import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
// import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
// import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
// import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.Path;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;

import org.onlab.packet.Ethernet;
// import org.onlab.packet.ICMP;
import org.onlab.packet.ICMP6;
import org.onlab.packet.IPv6;
import org.onlab.packet.MacAddress;
import org.onlab.packet.ARP;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.ndp.NeighborAdvertisement;
import org.onlab.packet.ndp.NeighborDiscoveryOptions;
import org.onlab.packet.ndp.NeighborSolicitation;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip6Address;

import java.net.URI;
import java.nio.ByteBuffer;

import org.onosproject.net.PortNumber;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ConnectPoint;

import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.store.service.ConsistentMap;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
import org.onlab.util.KryoNamespace;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class ProxyNdp {

    // TODO add bridge arp functions.
    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Some configurable property.
     */

    private final ConfigFactory<ApplicationId, Gateway> factory = new ConfigFactory<ApplicationId, Gateway>(
            APP_SUBJECT_FACTORY, Gateway.class, "proxyndp") {
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
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    private ProxyArpProcessor processor = new ProxyArpProcessor();

    private ApplicationId appId;

    // Distributed ARP/NDP tables (shared across instances/apps if needed)
    private ConsistentMap<IpAddress, HashMap<MacAddress, ConnectPoint>> arpConsistentMap;
    private Map<IpAddress, HashMap<MacAddress, ConnectPoint>> arpTable;
    private Map<DeviceId, HashMap<MacAddress, PortNumber>> bridgeTable = new HashMap<>();

    // Configuration values loaded from config
    private IpPrefix intraPrefix4;
    private IpPrefix intraPrefix6;
    private MacAddress intraMac;
    private DeviceId blockDeviceId;
    private List<DeviceId> targetDevices;

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
            log.warn("blockDeviceId not configured, skipping blockIngress");
            return;
        }
        TrafficTreatment drop = DefaultTrafficTreatment.builder().drop().build();
        TrafficTreatment punt = DefaultTrafficTreatment.builder().setOutput(PortNumber.CONTROLLER).build();

        // Base drops.
        pushRule(blockDeviceId, DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_ARP)
                .build(), drop, 62000);
        pushRule(blockDeviceId, DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV6)
                .matchIPProtocol(IPv6.PROTOCOL_ICMP6)
                .build(), drop, 62000);

        // Load allowed ARP pairs from config
        Gateway config = cfgRegistry.getConfig(appId, Gateway.class);
        if (config != null) {
            for (Gateway.IpPair pair : config.allowedArpPairs()) {
                allowToController(blockDeviceId, pair.srcIp(), pair.dstIp(), punt, 63000, 0);
            }
            for (Gateway.IpPair pair : config.allowedNdpPairs()) {
                allowToControllerV6(blockDeviceId, pair.srcIp(), pair.dstIp(), punt, 63000, 0);
            }
        } else {
            // Default values if no config
            allowToController(blockDeviceId, "192.168.70.253", "192.168.70.23", punt, 63000, 0);
            allowToController(blockDeviceId, "192.168.70.22", "192.168.70.23", punt, 63000, 0);
            allowToController(blockDeviceId, "192.168.70.24", "192.168.70.23", punt, 63000, 0);
            allowToControllerV6(blockDeviceId, "fd70::fe", "fd70::23", punt, 63000, 0);
            allowToControllerV6(blockDeviceId, "fd70::22", "fd70::23", punt, 63000, 0);
            allowToControllerV6(blockDeviceId, "fd70::24", "fd70::23", punt, 63000, 0);
        }
    }

    private void allowToController(DeviceId devId, String srcIp, String dstIp, TrafficTreatment punt, int priority,
            int inport) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        if (inport != 0) {
            selector.matchInPort(PortNumber.portNumber(inport));
        }

        pushRule(devId, selector
                .matchEthType(Ethernet.TYPE_ARP)
                .matchArpSpa(Ip4Address.valueOf(srcIp))
                .matchArpTpa(Ip4Address.valueOf(dstIp))
                .build(), punt, priority);
        // reverse direction
        pushRule(devId, selector
                .matchEthType(Ethernet.TYPE_ARP)
                .matchArpSpa(Ip4Address.valueOf(dstIp))
                .matchArpTpa(Ip4Address.valueOf(srcIp))
                .build(), punt, priority);
    }

    private void allowToControllerV6(DeviceId devId, String srcIp, String dstIp, TrafficTreatment punt, int priority,
            int inport) {
        // Neighbor Solicitation and Advertisement both include ND target; match icmp6
        // type to satisfy prereqs.
        pushNdRule(devId, srcIp, dstIp, ICMP6.NEIGHBOR_SOLICITATION, punt, priority, inport);
        pushNdRule(devId, srcIp, dstIp, ICMP6.NEIGHBOR_ADVERTISEMENT, punt, priority, inport);
        // reverse direction
        pushNdRule(devId, dstIp, srcIp, ICMP6.NEIGHBOR_SOLICITATION, punt, priority, inport);
        pushNdRule(devId, dstIp, srcIp, ICMP6.NEIGHBOR_ADVERTISEMENT, punt, priority, inport);
    }

    private void pushNdRule(DeviceId devId, String srcIp, String targetIp, byte icmpType,
            TrafficTreatment punt, int priority, int inport) {

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        if (inport != 0) {
            selector.matchInPort(PortNumber.portNumber(inport));
        }
        pushRule(devId, selector
                .matchEthType(Ethernet.TYPE_IPV6)
                .matchIPProtocol(IPv6.PROTOCOL_ICMP6)
                .matchIcmpv6Type(icmpType)
                .matchIPv6Src(IpPrefix.valueOf(srcIp + "/128"))
                .matchIPv6NDTargetAddress(Ip6Address.valueOf(targetIp))
                .build(), punt, priority);
    }

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nycu.winlab.proxyndp");

        // Register config factory
        cfgRegistry.registerConfigFactory(factory);
        cfgRegistry.addListener(cfgListener);

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

        // asJavaMap() provides a familiar Map facade; entries are kept in the
        // distributed store.
        arpTable = arpConsistentMap.asJavaMap();

        packetService.addProcessor(processor, PacketProcessor.director(3));

        // Load configuration and setup rules
        loadConfig();

        log.info("Started");
    }

    private void loadConfig() {
        Gateway config = cfgRegistry.getConfig(appId, Gateway.class);
        if (config == null) {
            // Use default values if no config
            log.info("No config found, using defaults");
            intraPrefix4 = IpPrefix.valueOf("172.16.23.0/24");
            intraPrefix6 = IpPrefix.valueOf("2a0b:4e07:c4:23::/64");
            blockDeviceId = DeviceId.deviceId("of:0000d2f4d1307942");
            targetDevices = new java.util.ArrayList<>();
            targetDevices.add(DeviceId.deviceId("of:0000000000000001"));
            targetDevices.add(DeviceId.deviceId("of:0000000000000002"));
            targetDevices.add(DeviceId.deviceId("of:0000d2f4d1307942"));

            // Default pre-add ARP entries
            upsertArpEntry(IpAddress.valueOf("192.168.63.1"), MacAddress.valueOf("00:00:23:00:00:04"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
            upsertArpEntry(IpAddress.valueOf("192.168.70.23"), MacAddress.valueOf("00:00:23:00:00:05"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));
            upsertArpEntry(IpAddress.valueOf("172.16.23.88"), MacAddress.valueOf("00:00:23:00:00:08"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(7)));
            upsertArpEntry(IpAddress.valueOf("172.16.23.88"), MacAddress.valueOf("00:00:23:00:00:09"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000002"), PortNumber.portNumber(7)));
            upsertArpEntry(IpAddress.valueOf("172.16.23.1"), MacAddress.valueOf("00:00:23:00:00:06"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(6)));

            upsertArpEntry(IpAddress.valueOf("fd63::1"), MacAddress.valueOf("00:00:23:00:00:04"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
            upsertArpEntry(IpAddress.valueOf("fd70::23"), MacAddress.valueOf("00:00:23:00:00:05"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));
            upsertArpEntry(IpAddress.valueOf("2a0b:4e07:c4:23::88"), MacAddress.valueOf("00:00:23:00:00:08"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(7)));
            upsertArpEntry(IpAddress.valueOf("2a0b:4e07:c4:23::88"), MacAddress.valueOf("00:00:23:00:00:09"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000002"), PortNumber.portNumber(7)));
            upsertArpEntry(IpAddress.valueOf("2a0b:4e07:c4:23::69"), MacAddress.valueOf("00:00:23:00:00:06"),
                    new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(6)));
        } else {
            intraPrefix4 = config.intraPrefix4();
            intraPrefix6 = config.intraPrefix6();
            intraMac = config.intraMac();
            blockDeviceId = config.blockDeviceId();
            targetDevices = config.targetDevices();

            // Load pre-add ARP entries from config
            for (Gateway.ArpEntry entry : config.preAddArpEntries()) {
                upsertArpEntry(entry.ip(), entry.mac(), entry.connectPoint());
            }
        }

        log.info("Config loaded: intraPrefix4={}, intraPrefix6={}, blockDeviceId={}",
                intraPrefix4, intraPrefix6, blockDeviceId);
        log.info("Target devices: {}", targetDevices);

        // Use FlowObjectiveService to request packets to controller
        TrafficTreatment punt = DefaultTrafficTreatment.builder()
                .setOutput(PortNumber.CONTROLLER)
                .build();

        // Request ARP packets to controller on all devices
        for (DeviceId deviceId : targetDevices) {
            pushRule(deviceId, DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_ARP)
                    .build(), punt, 61000);

            pushRule(deviceId, DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV6)
                    .matchIPProtocol(IPv6.PROTOCOL_ICMP6)
                    .matchIcmpv6Type(ICMP6.NEIGHBOR_ADVERTISEMENT)
                    .build(), punt, 61000);

            pushRule(deviceId, DefaultTrafficSelector.builder()
                    .matchEthType(Ethernet.TYPE_IPV6)
                    .matchIPProtocol(IPv6.PROTOCOL_ICMP6)
                    .matchIcmpv6Type(ICMP6.NEIGHBOR_SOLICITATION)
                    .build(), punt, 61000);
        }

        blockIngress();
    }

    private class InternalConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if (event.configClass() == Gateway.class) {
                switch (event.type()) {
                    case CONFIG_ADDED:
                    case CONFIG_UPDATED:
                        log.info("Network config updated, reloading...");
                        loadConfig();
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private Iterable<DeviceId> getTargetDevices() {
        // Return the devices that need packet-in rules
        if (targetDevices != null && !targetDevices.isEmpty()) {
            return targetDevices;
        }
        java.util.List<DeviceId> devices = new java.util.ArrayList<>();
        devices.add(DeviceId.deviceId("of:0000000000000001"));
        devices.add(DeviceId.deviceId("of:0000000000000002"));
        devices.add(DeviceId.deviceId("of:0000d2f4d1307942"));
        return devices;
    }

    @Deactivate
    protected void deactivate() {
        cfgRegistry.removeListener(cfgListener);
        cfgRegistry.unregisterConfigFactory(factory);

        flowRuleService.removeFlowRulesById(appId);

        packetService.removeProcessor(processor);
        processor = null;

        // Purge flow objectives for devices in bridgeTable
        for (DeviceId devId : bridgeTable.keySet()) {
            flowObjectiveService.purgeAll(devId, appId);
        }

        // Purge flow objectives for target devices
        if (targetDevices != null) {
            for (DeviceId devId : targetDevices) {
                flowObjectiveService.purgeAll(devId, appId);
            }
        } else {
            // Fallback to default devices
            flowObjectiveService.purgeAll(DeviceId.deviceId("of:0000d2f4d1307942"), appId);
            flowObjectiveService.purgeAll(DeviceId.deviceId("of:0000000000000002"), appId);
            flowObjectiveService.purgeAll(DeviceId.deviceId("of:0000000000000001"), appId);
        }

        log.info("Stopped");
    }

    private class ProxyArpProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null) {
                return;
            }

            DeviceId recDevId = pkt.receivedFrom().deviceId();
            PortNumber recPort = pkt.receivedFrom().port();
            bridgeTable.computeIfAbsent(recDevId, k -> new HashMap<>());

            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV6) {
                processIpv6(context, ethPkt, pkt, recDevId, recPort);
            } else if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                processArp(context, ethPkt, pkt, recDevId, recPort);
            }
        }
    }

    private void processIpv6(PacketContext context, Ethernet ethPkt, InboundPacket pkt,
            DeviceId recDevId, PortNumber recPort) {
        IPv6 ipv6Pkt = (IPv6) ethPkt.getPayload();
        if (ipv6Pkt.getNextHeader() != IPv6.PROTOCOL_ICMP6) {
            return;
        }

        IpAddress srcIp = IpAddress.valueOf(IpAddress.Version.INET6, ipv6Pkt.getSourceAddress());
        IpAddress dstIp = IpAddress.valueOf(IpAddress.Version.INET6, ipv6Pkt.getDestinationAddress());
        MacAddress senderMac = ethPkt.getSourceMAC();
        ICMP6 icmp6Pkt = (ICMP6) ipv6Pkt.getPayload();

        if (icmp6Pkt.getIcmpType() == ICMP6.NEIGHBOR_SOLICITATION) {
            NeighborSolicitation nsPkt = (NeighborSolicitation) icmp6Pkt.getPayload();
            IpAddress targetIp = IpAddress.valueOf(IpAddress.Version.INET6, nsPkt.getTargetAddress());

            updateBridgeTable(recDevId, senderMac, recPort);
            if (updateArpTableAndCheckNewMac(srcIp, senderMac, pkt.receivedFrom())) {
                return;
            }

            HashMap<MacAddress, ConnectPoint> targetNdp = arpTable.get(targetIp);
            log.info("Bridge Table : " + bridgeTable);
            if (targetNdp == null || targetNdp.isEmpty()) {
                log.info("INTRAPREFIX NDP MISS. Flood NDP SOLICITATION for " + targetIp +
                        " Device = " + recDevId + " Port = " + recPort);
                flood(context);
                return;
            }

            MacAddress targetMac = findNearestMac(targetNdp, pkt.receivedFrom(), true);
            boolean sameIntra = intraPrefix6.contains(srcIp) && intraPrefix6.contains(targetIp);
            boolean toRouter = targetMac.equals(intraMac);
            if (sameIntra && !toRouter) {
                PortNumber dstPort = bridgeTable.get(recDevId).get(targetMac);
                if (dstPort == null) {
                    log.info("NDP TABLE HIT but no BRIDGE TABLE ENTRY. Flood NDP SOLICITATION for " +
                            targetIp + " Device = " + recDevId + " Port = " + recPort);
                    flood(context);
                    return;
                }
                log.info("NDP SOLICITATION forwarding from " + srcIp + " to " + targetIp +
                        " using Device " + recDevId + " Port " + dstPort);
                packetOut(context, dstPort, null, null, null, null);
                learning(context, senderMac, targetMac, recDevId, dstPort, recPort);
            } else {
                packetOutNdp(targetIp, targetMac, ethPkt, recDevId, recPort);
            }

        } else if (icmp6Pkt.getIcmpType() == ICMP6.NEIGHBOR_ADVERTISEMENT) {
            updateBridgeTable(recDevId, senderMac, recPort);
            log.info("NDP Advertisement from " + srcIp + " to " + dstIp + "" +
                    " Device = " + recDevId + " Port = " + recPort + "Mac = " + senderMac);
            if (updateArpTableAndCheckNewMac(srcIp, senderMac, pkt.receivedFrom())) {
                return;
            }

            MacAddress dstMac = ethPkt.getDestinationMAC();
            boolean sameIntra = intraPrefix6.contains(srcIp) && intraPrefix6.contains(dstIp);
            PortNumber dstPort = bridgeTable.get(recDevId).get(dstMac);

            HashMap<MacAddress, ConnectPoint> targetEntry = arpTable.get(dstIp);
            HashMap<MacAddress, ConnectPoint> senderEntry = arpTable.get(srcIp);
            if (senderEntry.size() > 1) {
                ConnectPoint targePoint = targetEntry.get(dstMac);
                MacAddress nearestMac = findNearestMac(senderEntry, targePoint, true);
                if (!nearestMac.equals(senderMac)) {
                    return;
                }
            }
            if (dstPort == null || !sameIntra) {
                flood(context);
            } else {
                packetOut(context, dstPort, null, null, null, null);
                learning(context, senderMac, dstMac, recDevId, dstPort, recPort);
            }
        } else {
            return;
        }
    }

    private void processArp(PacketContext context, Ethernet ethPkt, InboundPacket pkt,
            DeviceId recDevId, PortNumber recPort) {
        ARP arpPkt = (ARP) ethPkt.getPayload();
        MacAddress senderMac = MacAddress.valueOf(arpPkt.getSenderHardwareAddress());
        IpAddress senderIp = IpAddress.valueOf(IpAddress.Version.INET, arpPkt.getSenderProtocolAddress());
        MacAddress targetMac = MacAddress.valueOf(arpPkt.getTargetHardwareAddress());
        IpAddress targetIp = IpAddress.valueOf(IpAddress.Version.INET, arpPkt.getTargetProtocolAddress());
        short op = arpPkt.getOpCode();

        if (bridgeTable.get(recDevId).get(senderMac) == null) {
            log.info("Insert BRIDGE TABLE. MAC = " + senderMac + ", Port = " + recPort,
                    " Device = " + recDevId);
            bridgeTable.get(recDevId).put(senderMac, recPort);
        }

        if (updateArpTableAndCheckNewMac(senderIp, senderMac, pkt.receivedFrom())) {
            return;
        }

        if (op == 0x1) {
            processArpRequest(context, ethPkt, pkt, recDevId, recPort, senderMac, senderIp, targetIp);
        } else {
            processArpReply(context, ethPkt, recDevId, recPort, senderMac, senderIp, targetMac, targetIp);
        }
    }

    private void processArpRequest(PacketContext context, Ethernet ethPkt, InboundPacket pkt,
            DeviceId recDevId, PortNumber recPort,
            MacAddress senderMac, IpAddress senderIp, IpAddress targetIp) {
        HashMap<MacAddress, ConnectPoint> targetEntry = arpTable.get(targetIp);
        if (targetEntry == null || targetEntry.isEmpty()) {
            log.info("INTRAPREFIX ARP MISS. Flood ARP Request for " + targetIp +
                    " Device = " + recDevId + " Port = " + recPort);
            flood(context);
            return;
        }

        MacAddress targetMac = findNearestMac(targetEntry, pkt.receivedFrom(), true);
        boolean sameIntra = intraPrefix4.contains(senderIp) && intraPrefix4.contains(targetIp);
        boolean toRouter = targetMac.equals(intraMac);
        if (sameIntra && !toRouter) {
            PortNumber dstPort = bridgeTable.get(recDevId).get(targetMac);
            if (dstPort == null) {
                log.info("ARP TABLE HIT but no BRIDGE TABLE ENTRY. Flood ARP Request for " +
                        targetIp + " Device = " + recDevId + " Port = " + recPort);
                flood(context);
                return;
            }
            log.info("ARP REQUEST forwarding from " + senderIp + " to " + targetIp +
                    " using Device " + recDevId + " Port " + dstPort);
            packetOut(context, dstPort, null, null, null, null);
            learning(context, ethPkt.getSourceMAC(), targetMac, recDevId, dstPort, recPort);
        } else {
            packetOut(context, pkt.receivedFrom().port(), targetMac, targetIp, senderMac, senderIp);
        }
    }

    private void processArpReply(PacketContext context, Ethernet ethPkt,
            DeviceId recDevId, PortNumber recPort,
            MacAddress senderMac, IpAddress senderIp,
            MacAddress targetMac, IpAddress targetIp) {
        boolean sameIntra = intraPrefix4.contains(senderIp) && intraPrefix4.contains(targetIp);
        PortNumber dstPort = bridgeTable.get(recDevId).get(targetMac);
        // From targetMac's view, check whether senderMac is the nearest one to
        // targetMac.
        HashMap<MacAddress, ConnectPoint> targetEntry = arpTable.get(targetIp);
        HashMap<MacAddress, ConnectPoint> senderEntry = arpTable.get(senderIp);
        if (senderEntry.size() > 1) {
            ConnectPoint targePoint = targetEntry.get(targetMac);
            MacAddress nearestMac = findNearestMac(senderEntry, targePoint, true);
            if (!nearestMac.equals(senderMac)) {
                log.info("Not the nearest MAC. Drop ARP REPLY from " + senderIp + " to " + targetIp);
                return;
            }
        }
        if (dstPort == null || !sameIntra) {
            log.info("Bridge Table : " + bridgeTable);
            flood(context);
        } else {
            log.info("ARP REPLY forwarding from " + senderIp + " to " + targetIp +
                    " using Device " + recDevId + " Port " + dstPort);
            packetOut(context, dstPort, null, null, null, null);
            learning(context, ethPkt.getSourceMAC(), targetMac, recDevId, dstPort, recPort);
        }
    }

    private void updateBridgeTable(DeviceId devId, MacAddress mac, PortNumber port) {
        if (bridgeTable.get(devId).get(mac) == null) {
            bridgeTable.get(devId).put(mac, port);
        }
    }

    private boolean updateArpTableAndCheckNewMac(IpAddress ip, MacAddress mac, ConnectPoint cp) {
        HashMap<MacAddress, ConnectPoint> entry = arpTable.get(ip);
        if (entry == null) {
            upsertArpEntry(ip, mac, cp);
            return false;
        } else if (entry.get(mac) == null) {
            upsertArpEntry(ip, mac, cp);
            return true;
        }
        return false;
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

    private void printBridgeTable() {
        log.info("Bridge Table:");
        for (DeviceId devId : bridgeTable.keySet()) {
            log.info(" Device: " + devId.toString());
            HashMap<MacAddress, PortNumber> table = bridgeTable.get(devId);
            for (MacAddress mac : table.keySet()) {
                PortNumber port = table.get(mac);
                log.info("  MAC: " + mac.toString() + " -> Port: " + port.toString());
            }
        }
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

    private void upsertArpEntry(IpAddress ip, MacAddress mac, ConnectPoint cp) {
        arpTable.compute(ip, (k, v) -> {
            HashMap<MacAddress, ConnectPoint> map = (v == null)
                    ? new HashMap<MacAddress, ConnectPoint>()
                    : new HashMap<MacAddress, ConnectPoint>(v);
            map.put(mac, cp);
            return map;
        });
    }

    private void flood(PacketContext context) {
        packetOut(context, PortNumber.FLOOD, null, null, null, null);
    }

    private void packetOutNdp(IpAddress srcIp,
            MacAddress srcMac,
            Ethernet ethPkt,
            DeviceId devId,
            PortNumber port) {
        IPv6 ipv6Request = (IPv6) ethPkt.getPayload();

        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(ethPkt.getSourceMAC());
        eth.setSourceMACAddress(srcMac);
        eth.setEtherType(Ethernet.TYPE_IPV6);
        eth.setVlanID(ethPkt.getVlanID());

        IPv6 ipv6 = new IPv6();
        ipv6.setSourceAddress(srcIp.toOctets());
        ipv6.setDestinationAddress(ipv6Request.getSourceAddress());
        ipv6.setHopLimit((byte) 255); // IMPORTANT
        ipv6.setNextHeader(IPv6.PROTOCOL_ICMP6);

        ICMP6 icmp6 = new ICMP6();
        icmp6.setIcmpType(ICMP6.NEIGHBOR_ADVERTISEMENT);
        icmp6.setIcmpCode((byte) 0);

        NeighborAdvertisement nadv = new NeighborAdvertisement();
        nadv.setTargetAddress(srcIp.toOctets());
        nadv.setSolicitedFlag((byte) 1);
        nadv.setOverrideFlag((byte) 1);
        nadv.addOption(NeighborDiscoveryOptions.TYPE_TARGET_LL_ADDRESS,
                srcMac.toBytes());

        icmp6.setPayload(nadv);
        ipv6.setPayload(icmp6);
        eth.setPayload(ipv6);

        OutboundPacket oPkt = new DefaultOutboundPacket(devId,
                DefaultTrafficTreatment.builder().setOutput(port).build(),
                ByteBuffer.wrap(eth.serialize()));

        packetService.emit(oPkt);
    }

    private void packetOut(PacketContext context,
            PortNumber port,
            MacAddress targetMac,
            IpAddress targetIp,
            MacAddress senderMac,
            IpAddress senderIp) {

        if (targetIp != null) {
            ARP arp = new ARP();
            arp.setOpCode((short) 0x2);
            arp.setProtocolType(ARP.PROTO_TYPE_IP);
            arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
            arp.setProtocolAddressLength((byte) Ip4Address.BYTE_LENGTH);
            arp.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
            arp.setTargetHardwareAddress(senderMac.toBytes());
            arp.setSenderHardwareAddress(targetMac.toBytes());
            arp.setTargetProtocolAddress(ByteBuffer.wrap(senderIp.toOctets()).getInt());
            arp.setSenderProtocolAddress(ByteBuffer.wrap(targetIp.toOctets()).getInt());
            Ethernet eth = new Ethernet();
            eth.setDestinationMACAddress(senderMac);
            eth.setSourceMACAddress(targetMac);
            eth.setEtherType(Ethernet.TYPE_ARP);
            eth.setPayload(arp);
            OutboundPacket oPkt = new DefaultOutboundPacket(
                    context.inPacket().receivedFrom().deviceId(),
                    DefaultTrafficTreatment.builder().setOutput(port).build(),
                    ByteBuffer.wrap(eth.serialize()));
            packetService.emit(oPkt);
        } else {
            context.treatmentBuilder().setOutput(port);
            context.send();
        }

    }

    private void learning(PacketContext context, MacAddress srcMac, MacAddress dstMac, DeviceId devId,
            PortNumber outport, PortNumber inport) {
        log.info("Install learning flow rule: " + srcMac.toString() + " -> " + dstMac.toString() +
                " via Device " + devId.toString() +
                " OutPort " + outport.toString() +
                " InPort " + inport.toString());
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthSrc(srcMac)
                .matchEthDst(dstMac)
                .build();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outport)
                .build();
        FlowRule flowRule = DefaultFlowRule.builder()
                .forDevice(devId)
                .withSelector(selector)
                .withTreatment(treatment)
                .withPriority(64000)
                .fromApp(appId)
                .makeTemporary(60)
                .build();
        flowRuleService.applyFlowRules(flowRule);

        TrafficSelector reverseSelector = DefaultTrafficSelector.builder()
                .matchEthSrc(dstMac)
                .matchEthDst(srcMac)
                .build();
        TrafficTreatment reverseTreatment = DefaultTrafficTreatment.builder()
                .setOutput(inport)
                .build();
        FlowRule reverseFlowRule = DefaultFlowRule.builder()
                .forDevice(devId)
                .withSelector(reverseSelector)
                .withTreatment(reverseTreatment)
                .withPriority(64000)
                .fromApp(appId)
                .makeTemporary(60)
                .build();
        flowRuleService.applyFlowRules(reverseFlowRule);

    }
}

// mvn clean install -DskipTests
// onos-app localhost install! ./target
// sudo mn --controller=remote,127.0.0.1:6653 --topo=tree,depth=2
// --switch=ovs,protocols=OpenFlow14
