/*
 * Copyright 2020-present Open Networking Foundation
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
package nycu.interdomain;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration facade for the interdomain app.
 */
public class Gateway extends Config<ApplicationId> {

    private static final String INTRA_PREFIX_4 = "intraPrefix4";
    private static final String INTRA_PREFIX_6 = "intraPrefix6";
    private static final String INTRA_MAC = "intraMac";
    private static final String BLOCK_DEVICE_ID = "blockDeviceId";
    private static final String GATEWAYS = "gateways";
    private static final String PEERS = "peers";

    /**
     * Gets the IPv4 intra-domain prefix.
     *
     * @return IPv4 prefix
     */
    public IpPrefix intraPrefix4() {
        String prefix = get(INTRA_PREFIX_4, "172.16.23.0/24");
        return IpPrefix.valueOf(prefix);
    }

    /**
     * Gets the IPv6 intra-domain prefix.
     *
     * @return IPv6 prefix
     */
    public IpPrefix intraPrefix6() {
        String prefix = get(INTRA_PREFIX_6, "2a0b:4e07:c4:23::/64");
        return IpPrefix.valueOf(prefix);
    }

    /**
     * Gets the intra-domain MAC address.
     *
     * @return MAC address
     */
    public MacAddress intraMac() {
        String mac = get(INTRA_MAC, "00:00:23:00:00:06");
        return MacAddress.valueOf(mac);
    }

    /**
     * Gets the device ID for blocking ingress traffic.
     *
     * @return Device ID
     */
    public DeviceId blockDeviceId() {
        String devId = get(BLOCK_DEVICE_ID, "of:0000d2f4d1307942");
        return DeviceId.deviceId(devId);
    }

    /**
     * Gets the gateway mappings (IP -> ConnectPoint).
     *
     * @return Map of gateway IPs to ConnectPoints
     */
    public Map<IpAddress, ConnectPoint> gateways() {
        Map<IpAddress, ConnectPoint> result = new HashMap<>();
        JsonNode gatewaysNode = object.get(GATEWAYS);
        if (gatewaysNode != null && gatewaysNode.isArray()) {
            for (JsonNode gw : gatewaysNode) {
                String ip = gw.get("ip").asText();
                String deviceId = gw.get("deviceId").asText();
                int port = gw.get("port").asInt();
                result.put(IpAddress.valueOf(ip),
                        new ConnectPoint(DeviceId.deviceId(deviceId), PortNumber.portNumber(port)));
            }
        }
        return result;
    }

    /**
     * Gets the list of BGP peer IP addresses.
     *
     * @return List of peer IPs
     */
    public List<IpAddress> peers() {
        List<IpAddress> result = new ArrayList<>();
        JsonNode peersNode = object.get(PEERS);
        if (peersNode != null && peersNode.isArray()) {
            for (JsonNode peer : peersNode) {
                result.add(IpAddress.valueOf(peer.asText()));
            }
        }
        return result;
    }
}
