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
import java.util.HashSet;
import java.util.Map;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
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

import org.onosproject.net.device.DeviceService;
import org.onosproject.net.packet.PacketPriority;
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

    private ProxyArpProcessor processor = new ProxyArpProcessor();

    private ApplicationId appId;

    // Distributed ARP/NDP tables (shared across instances/apps if needed)
    private ConsistentMap<IpAddress, HashMap<MacAddress, ConnectPoint>> arpConsistentMap;
    private Map<IpAddress, HashMap<MacAddress, ConnectPoint>> arpTable;
    private Map<DeviceId, HashMap<MacAddress, PortNumber>> bridgeTable = new HashMap<>();
    private static final IpPrefix INTRAPREFIX4 = IpPrefix.valueOf("172.16.23.0/24");
    private static final IpPrefix INTRAPREFIX6 = IpPrefix.valueOf("2a0b:4e07:c4:23::/64");

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
        DeviceId devId = DeviceId.deviceId("of:0000d2f4d1307942");
        DeviceId devId2 = DeviceId.deviceId("of:0000000000000002");
        TrafficTreatment drop = DefaultTrafficTreatment.builder().drop().build();
        TrafficTreatment punt = DefaultTrafficTreatment.builder().setOutput(PortNumber.CONTROLLER).build();

        // Base drops.
        pushRule(devId, DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_ARP)
                .build(), drop, 62000);
        pushRule(devId, DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV6)
                .matchIPProtocol(IPv6.PROTOCOL_ICMP6)
                .build(), drop, 62000);

        // Allowed ARP ICMPv6 pairs (both directions) punted to controller.
        allowToController(devId, "192.168.70.253", "192.168.70.23", punt, 63000, 0);
        allowToController(devId2, "192.168.70.254", "192.168.70.23", punt, 63000, 0);
        allowToController(devId, "192.168.70.22", "192.168.70.23", punt, 63000, 0);
        allowToController(devId, "192.168.70.24", "192.168.70.23", punt, 63000, 0);
        allowToControllerV6(devId, "fd70::fe", "fd70::23", punt, 63000, 0);
        allowToControllerV6(devId, "fd70::ff", "fd70::23", punt, 63000, 0);
        allowToControllerV6(devId, "fd70::22", "fd70::23", punt, 63000, 0);
        allowToControllerV6(devId, "fd70::24", "fd70::23", punt, 63000, 0);
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

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.requestPackets(selector.build(), PacketPriority.HIGH1, appId);

        selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV6);
        selector.matchIPProtocol(IPv6.PROTOCOL_ICMP6);
        selector.matchIcmpv6Type(ICMP6.NEIGHBOR_ADVERTISEMENT);
        packetService.requestPackets(selector.build(), PacketPriority.HIGH1, appId);

        selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV6);
        selector.matchIPProtocol(IPv6.PROTOCOL_ICMP6);
        selector.matchIcmpv6Type(ICMP6.NEIGHBOR_SOLICITATION);
        packetService.requestPackets(selector.build(), PacketPriority.HIGH1, appId);

        upsertArpEntry(IpAddress.valueOf("192.168.63.1"), MacAddress.valueOf("00:00:23:00:00:04"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
        upsertArpEntry(IpAddress.valueOf("192.168.70.23"), MacAddress.valueOf("00:00:23:00:00:05"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));

        upsertArpEntry(IpAddress.valueOf("fd63::1"), MacAddress.valueOf("00:00:23:00:00:04"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(4)));
        upsertArpEntry(IpAddress.valueOf("fd70::23"), MacAddress.valueOf("00:00:23:00:00:05"),
                new ConnectPoint(DeviceId.deviceId("of:0000000000000001"), PortNumber.portNumber(5)));
        blockIngress();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {

        flowRuleService.removeFlowRulesById(appId);

        packetService.removeProcessor(processor);
        processor = null;

        // Purge flow objectives for devices in bridgeTable
        for (DeviceId devId : bridgeTable.keySet()) {
            flowObjectiveService.purgeAll(devId, appId);
        }

        // Purge flow objectives for devices used in blockIngress()
        DeviceId devId1 = DeviceId.deviceId("of:0000d2f4d1307942");
        DeviceId devId2 = DeviceId.deviceId("of:0000000000000002");
        flowObjectiveService.purgeAll(devId1, appId);
        flowObjectiveService.purgeAll(devId2, appId);

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.cancelPackets(selector.build(), PacketPriority.HIGH1, appId);

        selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV6);
        selector.matchIPProtocol(IPv6.PROTOCOL_ICMP6);
        selector.matchIcmpv6Type(ICMP6.NEIGHBOR_ADVERTISEMENT);
        packetService.cancelPackets(selector.build(), PacketPriority.HIGH1, appId);

        selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV6);
        selector.matchIPProtocol(IPv6.PROTOCOL_ICMP6);
        selector.matchIcmpv6Type(ICMP6.NEIGHBOR_SOLICITATION);
        packetService.cancelPackets(selector.build(), PacketPriority.HIGH1, appId);

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
            if (bridgeTable.get(pkt.receivedFrom().deviceId()) == null) {
                bridgeTable.put(pkt.receivedFrom().deviceId(), new HashMap<MacAddress, PortNumber>());
            }

            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV6) {
                IPv6 ipv6Pkt = (IPv6) ethPkt.getPayload();
                if (ipv6Pkt.getNextHeader() != IPv6.PROTOCOL_ICMP6) {
                    return;
                }
                IpAddress srcIp = IpAddress.valueOf(IpAddress.Version.INET6,
                        ipv6Pkt.getSourceAddress());
                IpAddress dstIp = IpAddress.valueOf(IpAddress.Version.INET6,
                        ipv6Pkt.getDestinationAddress());
                MacAddress senderMac = ethPkt.getSourceMAC();

                ICMP6 icmp6Pkt = (ICMP6) ipv6Pkt.getPayload();

                DeviceId recDevId = pkt.receivedFrom().deviceId();
                PortNumber recPort = pkt.receivedFrom().port();

                if (icmp6Pkt.getIcmpType() == ICMP6.NEIGHBOR_SOLICITATION) {
                    // log.info("RECV NDP SOL.");
                    NeighborSolicitation nsPkt = (NeighborSolicitation) icmp6Pkt.getPayload();
                    IpAddress targetIp = IpAddress.valueOf(IpAddress.Version.INET6,
                            nsPkt.getTargetAddress());

                    if (arpTable.get(srcIp) == null || arpTable.get(srcIp).get(senderMac) == null) {
                        // log.info("Insert NDP TABLE. IP = " + srcIp + ", MAC = " + senderMac);
                        upsertArpEntry(srcIp, senderMac, pkt.receivedFrom());
                    }

                    if (bridgeTable.get(recDevId).get(senderMac) == null) {
                        bridgeTable.get(recDevId).put(senderMac, recPort);
                    }

                    // NDP TABLE HIT. Requested MAC = {MAC}
                    // NDP TABLE MISS. Send NDP Solicitation to edge ports
                    HashMap<MacAddress, ConnectPoint> targetNdp = arpTable.get(targetIp);
                    if (targetNdp == null || targetNdp.isEmpty()) {
                        // log.info("INTRAPREFIX NDP MISS. Flood NDP Solicitation for " +
                        // targetIp.toString());
                        flood(context, srcIp, targetIp);
                    } else {
                        MacAddress targetMac = null;
                        if (targetNdp.size() > 1) {
                            ConnectPoint sender = pkt.receivedFrom();
                            for (MacAddress mac : targetNdp.keySet()) {
                                ConnectPoint cp = targetNdp.get(mac);
                                if (near(sender, cp)) {
                                    targetMac = mac;
                                    break;
                                }
                            }
                        } else if (!targetNdp.isEmpty()) {
                            targetMac = targetNdp.keySet().iterator().next();
                        }
                        // log.info("NDP TABLE HIT. Requested MAC = " + targetMac);
                        boolean sameIntra6 = INTRAPREFIX6.contains(srcIp) && INTRAPREFIX6.contains(dstIp);
                        if (sameIntra6) {
                            // bridge ndp
                            log.info("NDP SOLICITATION forwarding from " + srcIp.toString() + " to "
                                    + targetIp.toString()
                                    + " using Device " + recDevId.toString() +
                                    " Port " + bridgeTable.get(recDevId).get(targetMac).toString());
                            PortNumber dstPort = bridgeTable.get(recDevId).get(targetMac);
                            packetOut(context, dstPort, null, null, null, null);
                            learning(context, ethPkt.getSourceMAC(), targetMac, recDevId, dstPort, recPort);
                            return;
                        } else {
                            // proxy ndp
                            packetOutNdp(targetIp, targetMac, ethPkt, recDevId, recPort);
                        }
                    }
                } else if (icmp6Pkt.getIcmpType() == ICMP6.NEIGHBOR_ADVERTISEMENT) {
                    if (arpTable.get(srcIp) == null) {
                        // log.info("Insert NDP TABLE. IP = " + srcIp + ", MAC = " + senderMac);
                        upsertArpEntry(srcIp, senderMac, pkt.receivedFrom());
                    }
                    // bridge forwarding
                    MacAddress dstMac = ethPkt.getDestinationMAC();
                    boolean sameIntra6 = INTRAPREFIX6.contains(srcIp) && INTRAPREFIX6.contains(dstIp);
                    if (bridgeTable.get(recDevId).get(dstMac) == null || !sameIntra6) {
                        flood(context, srcIp, dstIp);
                    } else {
                        PortNumber dstPort = bridgeTable.get(recDevId).get(dstMac);
                        packetOut(context, dstPort, null, null, null, null);
                        learning(context, ethPkt.getSourceMAC(), dstMac, recDevId, dstPort, recPort);
                    }
                }

            } else if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
                ARP arpPkt = (ARP) ethPkt.getPayload();
                MacAddress senderMac = MacAddress.valueOf(arpPkt.getSenderHardwareAddress());
                IpAddress senderIp = IpAddress.valueOf(IpAddress.Version.INET,
                        arpPkt.getSenderProtocolAddress());

                MacAddress targetMac = MacAddress.valueOf(arpPkt.getTargetHardwareAddress());
                IpAddress targetIp = IpAddress.valueOf(IpAddress.Version.INET,
                        arpPkt.getTargetProtocolAddress());

                short op = arpPkt.getOpCode();

                DeviceId recDevId = pkt.receivedFrom().deviceId();
                PortNumber recPort = pkt.receivedFrom().port();
                // sender is not in arp table
                if (arpTable.get(senderIp) == null || arpTable.get(senderIp).get(senderMac) == null) {
                    upsertArpEntry(senderIp, senderMac, pkt.receivedFrom());
                }

                if (bridgeTable.get(recDevId).get(senderMac) == null) {
                    log.info("Insert BRIDGE TABLE. MAC = " + senderMac + ", Port = " + recPort.toString(),
                            " Device = " + recDevId.toString());
                    bridgeTable.get(recDevId).put(senderMac, recPort);
                }

                if (op == 0x1) {

                    HashMap<MacAddress, ConnectPoint> targetEntry = arpTable.get(targetIp);
                    if (targetEntry == null || targetEntry.isEmpty()) {
                        log.info("INTRAPREFIX ARP MISS. Flood ARP Request for " + targetIp.toString() +
                                " Device = " + recDevId.toString() +
                                " Port = " + recPort.toString());
                        flood(context, senderIp, targetIp);
                    } else {
                        if (targetEntry.size() > 1) {
                            ConnectPoint sender = pkt.receivedFrom();
                            for (MacAddress mac : targetEntry.keySet()) {
                                ConnectPoint cp = targetEntry.get(mac);
                                if (near(sender, cp)) {
                                    targetMac = mac;
                                    break;
                                }
                            }
                        } else if (!targetEntry.isEmpty()) {
                            targetMac = targetEntry.keySet().iterator().next();
                        }
                        // log.info("ARP TABLE HIT. Requested MAC = " + targetMac);
                        boolean sameIntra4 = INTRAPREFIX4.contains(senderIp) && INTRAPREFIX4.contains(targetIp);
                        if (sameIntra4) {
                            // bridge arp
                            log.info("ARP REQUEST forwarding from " + senderIp.toString() + " to " + targetIp.toString()
                                    + " using Device " + recDevId.toString() +
                                    " Port " + bridgeTable.get(recDevId).get(targetMac).toString());
                            PortNumber dstPort = bridgeTable.get(recDevId).get(targetMac);
                            packetOut(context, dstPort, null, null, null, null);
                            learning(context, ethPkt.getSourceMAC(), targetMac, recDevId, dstPort, recPort);
                            return;
                        } else {
                            // proxy arp
                            packetOut(context, pkt.receivedFrom().port(), targetMac, targetIp, senderMac, senderIp);
                        }
                    }
                } else {
                    boolean sameIntra4 = INTRAPREFIX4.contains(senderIp) && INTRAPREFIX4.contains(targetIp);
                    if (bridgeTable.get(recDevId).get(targetMac) == null || !sameIntra4) {
                        log.info("INTRAPREFIX ARP REPLY MISS. Flood ARP Reply from " +
                                senderIp.toString() + " to " + targetIp.toString() + " Device = " + recDevId.toString()
                                +
                                " Port = " + recPort.toString());
                        log.info("Bridge Table : " + bridgeTable.toString());
                        flood(context, senderIp, targetIp);
                    } else {
                        log.info("ARP REPLY forwarding from " + senderIp.toString() + " to " + targetIp.toString()
                                + " using Device " + recDevId.toString() +
                                " Port " + bridgeTable.get(recDevId).get(targetMac).toString());
                        PortNumber dstPort = bridgeTable.get(recDevId).get(targetMac);
                        packetOut(context, dstPort, null, null, null, null);
                        learning(context, ethPkt.getSourceMAC(), targetMac, recDevId, dstPort, recPort);
                    }
                }
            }

        }
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

    private void upsertArpEntry(IpAddress ip, MacAddress mac, ConnectPoint cp) {
        arpTable.compute(ip, (k, v) -> {
            HashMap<MacAddress, ConnectPoint> map = (v == null)
                    ? new HashMap<MacAddress, ConnectPoint>()
                    : new HashMap<MacAddress, ConnectPoint>(v);
            map.put(mac, cp);
            return map;
        });
    }

    private class PairIp {
        private IpAddress srcIp;
        private IpAddress dstIp;

        public PairIp(IpAddress src, IpAddress dst) {
            this.srcIp = src;
            this.dstIp = dst;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PairIp pairIp = (PairIp) o;
            return srcIp.equals(pairIp.srcIp) && dstIp.equals(pairIp.dstIp);
        }

        @Override
        public int hashCode() {
            return srcIp.hashCode() * 31 + dstIp.hashCode();
        }
    }

    private HashSet<PairIp> usedContexts = new HashSet<>();

    private void flood(PacketContext context, IpAddress srcIp, IpAddress dstIp) {
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
