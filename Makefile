CONTAINERS := H1 H3 H2 R2 FRR ONOS
OVS_BRIDGE := ovs-br1 ovs-br2

# You're ID for the lab.
IP_SETTING := 23
IP_SETTING2 := 22
IP_SETTING3 := 24

# MTU setting for all interfaces
MTU_SIZE := 1370

CONTAINER_TO_BRIDGE :=
CONTAINER_TO_BRIDGE += H1@ovs-br2@172.16.$(IP_SETTING).2/24,2a0b:4e07:c4:$(IP_SETTING)::2/64@veth-h1@00:00:$(IP_SETTING):00:00:01
CONTAINER_TO_BRIDGE += H2@ovs-br1@172.16.$(IP_SETTING).3/24,2a0b:4e07:c4:$(IP_SETTING)::3/64@veth-h2@00:00:$(IP_SETTING):00:00:03
CONTAINER_TO_BRIDGE += R2@ovs-br1@192.168.63.2/24,fd63::2/64@veth-r2@00:00:$(IP_SETTING):00:00:02

CONTAINER_TO_BRIDGE += FRR@ovs-br1@192.168.63.1/24,fd63::1/64@veth-frr63@00:00:$(IP_SETTING):00:00:04
CONTAINER_TO_BRIDGE += FRR@ovs-br1@192.168.70.$(IP_SETTING)/24,fd70::$(IP_SETTING)/64@veth-frr70@00:00:$(IP_SETTING):00:00:05
CONTAINER_TO_BRIDGE += FRR@ovs-br1@172.16.$(IP_SETTING).1/24,2a0b:4e07:c4:$(IP_SETTING)::69/64@veth-frr16@00:00:$(IP_SETTING):00:00:06
# CONTAINER_TO_BRIDGE += FRR@ovs-br1@192.168.100.3/24@veth-frrmgmt@00:00:$(IP_SETTING):00:00:07

CONTAINER_TO_CONTAINER :=
CONTAINER_TO_CONTAINER += H3@R2@172.17.$(IP_SETTING).2/24,2a0b:4e07:c4:1$(IP_SETTING)::2/64@172.17.$(IP_SETTING).1/24,2a0b:4e07:c4:1$(IP_SETTING)::1/64

CONTAINER_DEFAULT_IF := 
CONTAINER_DEFAULT_GW := H3:172.17.$(IP_SETTING).1,2a0b:4e07:c4:1$(IP_SETTING)::1 H1:172.16.$(IP_SETTING).1,2a0b:4e07:c4:$(IP_SETTING)::69 H2:172.16.$(IP_SETTING).1,2a0b:4e07:c4:$(IP_SETTING)::69
CONTAINER_DEFAULT_GW += R2:192.168.63.1,fd63::1

# 0e:a7:1a:c5:29:15 , 192.168.70.253 's mac address

.PHONY: start

# Default target: Do everything


start: up config_frr ovs-setup connect routes setup_onos start_frr check
	
restart: reup config_frr ovs-setup connect routes arp-setup setup_onos start_frr check

# Start containers
up:
	docker compose up -d --force-recreate

reup:
	docker compose up -d

# Stop containers
down:
	docker compose down

# Clean up OVS bridge and containers
stop: down
	@for br in $(OVS_BRIDGE); do \
		sudo ovs-vsctl --if-exists del-br $$br; \
	done
	sudo ovs-vsctl --if-exists del-br ovs-mgmt
	sudo ip link delete veth-local 2>/dev/null || true
	sudo wg-quick down wg0

# Create Open vSwitch bridge
ovs-setup:

	if ! sudo wg show wg0 >/dev/null 2>&1; then \
		echo "Starting WireGuard interface wg0..."; \
		sudo wg-quick up wg0; \
	else \
		echo "WireGuard interface wg0 is already running."; \
	fi

	@for br in $(OVS_BRIDGE); do \
		id=$${br#ovs-br}; \
		dpid=$$(printf "00000000000000%02d" $$id); \
		sudo ovs-vsctl --may-exist add-br $$br -- set bridge $$br protocols=OpenFlow14 -- set-controller $$br tcp:192.168.100.2:6653 -- set bridge $$br other-config:datapath-id=$$dpid; \
		sudo ip link set $$br up; \
	done


	sudo ip link set ovs-br1 mtu $(MTU_SIZE)
	sudo ip link set ovs-br2 mtu $(MTU_SIZE)
	sudo ip link set ovs-br1 up
	sudo ip link set ovs-br2 up

	sudo ovs-vsctl --may-exist add-port ovs-br1 patch-br2 -- set interface patch-br2 type=patch options:peer=patch-br1
	sudo ovs-vsctl --may-exist add-port ovs-br2 patch-br1 -- set interface patch-br1 type=patch options:peer=patch-br2
	

	sudo ovs-vsctl --may-exist add-port ovs-br2 TO_VXLAN -- set interface TO_VXLAN type=vxlan options:remote_ip=192.168.60.$(IP_SETTING) -- set interface TO_VXLAN mtu_request=$(MTU_SIZE)
	sudo ovs-vsctl --may-exist add-port ovs-br2 TO_VXLAN2 -- set interface TO_VXLAN2 type=vxlan options:remote_ip=192.168.60.$(IP_SETTING2) -- set interface TO_VXLAN2 mtu_request=$(MTU_SIZE)
	sudo ovs-vsctl --may-exist add-port ovs-br2 TO_VXLAN3 -- set interface TO_VXLAN3 type=vxlan options:remote_ip=192.168.60.$(IP_SETTING3) -- set interface TO_VXLAN3 mtu_request=$(MTU_SIZE)



	sudo ip link add veth-local type veth peer name veth-peer

	sudo ovs-vsctl --may-exist add-port ovs-br2 veth-local -- set interface veth-local mtu_request=$(MTU_SIZE)

	sudo ip link set up veth-local
	sudo ip link set up veth-peer

	sudo ip address add 192.168.100.2/24 dev veth-peer


# Connect containers to OVS bridge via veth pairs
connect:
	@for mapping in $(CONTAINER_TO_BRIDGE); do \
		container=$${mapping%%@*}; \
		rest=$${mapping#*@}; \
		bridge=$${rest%%@*}; \
		rest=$${rest#*@}; \
		ip_addr=$${rest%%@*}; \
		rest=$${rest#*@}; \
		veth_host=$${rest%%@*}; \
		mac_addr=$${rest#*@}; \
		echo "Configuring network for $$container on $$bridge with IP $$ip_addr..."; \
		PID=$$(docker inspect -f '{{.State.Pid}}' $$container); \
		if [ -z "$${PID}" ] || [ "$${PID}" = "0" ]; then \
			echo "Error: $$container is not running."; \
			continue; \
		fi; \
		VETH_HOST="$$veth_host"; \
		VETH_CONT="vpeer_$${veth_host#veth-}"; \
		sudo ip link delete $$VETH_HOST 2>/dev/null || true; \
		sudo ip link add $$VETH_HOST type veth peer name $$VETH_CONT; \
		sudo ovs-vsctl --may-exist add-port $$bridge $$VETH_HOST; \
		sudo ip link set $$VETH_HOST mtu $(MTU_SIZE); \
		sudo ip link set $$VETH_HOST up; \
		sudo ip link set $$VETH_CONT netns $$PID; \
		ETH_ID=0; \
		while sudo nsenter -t $$PID -n ip link show eth$$ETH_ID >/dev/null 2>&1; do \
			ETH_ID=$$((ETH_ID+1)); \
		done; \
		IFACE_NAME="eth$$ETH_ID"; \
		sudo nsenter -t $$PID -n ip link set $$VETH_CONT name $$IFACE_NAME; \
		sudo nsenter -t $$PID -n ip link set $$IFACE_NAME mtu $(MTU_SIZE); \
		sudo nsenter -t $$PID -n ip link set $$IFACE_NAME address $$mac_addr; \
		sudo nsenter -t $$PID -n ip link set $$IFACE_NAME up; \
		if [ "$$ip_addr" != "0" ]; then \
			for ip in $$(echo $$ip_addr | tr ',' ' '); do \
				sudo nsenter -t $$PID -n ip addr add $$ip dev $$IFACE_NAME; \
				echo "Assigned IP $$ip to $$container ($$IFACE_NAME)"; \
			done; \
		fi; \
		echo "Connected $$container to $$bridge via $$VETH_HOST <-> $$IFACE_NAME"; \
	done
	@for mapping in $(CONTAINER_TO_CONTAINER); do \
		c1=$${mapping%%@*}; \
		rest=$${mapping#*@}; \
		c2=$${rest%%@*}; \
		rest=$${rest#*@}; \
		ip1=$${rest%%@*}; \
		ip2=$${rest#*@}; \
		echo "Connecting $$c1 and $$c2..."; \
		PID1=$$(docker inspect -f '{{.State.Pid}}' $$c1); \
		PID2=$$(docker inspect -f '{{.State.Pid}}' $$c2); \
		if [ -z "$$PID1" ] || [ "$$PID1" = "0" ]; then echo "Error: $$c1 not running"; continue; fi; \
		if [ -z "$$PID2" ] || [ "$$PID2" = "0" ]; then echo "Error: $$c2 not running"; continue; fi; \
		SUFFIX=$$(printf "%s" "$$mapping" | md5sum | cut -c1-4); \
		CNAME1_SHORT=$$(echo $$c1 | cut -c1-5); \
		CNAME2_SHORT=$$(echo $$c2 | cut -c1-5); \
		VETH1="veth_$${CNAME1_SHORT}_$$SUFFIX"; \
		VETH3="vpeer_$${CNAME2_SHORT}_$$SUFFIX"; \
		sudo ip link delete $$VETH1 2>/dev/null || true; \
		sudo ip link add $$VETH1 type veth peer name $$VETH3; \
		sudo ip link set $$VETH1 mtu $(MTU_SIZE); \
		sudo ip link set $$VETH3 mtu $(MTU_SIZE); \
		sudo ip link set $$VETH1 netns $$PID1; \
		sudo ip link set $$VETH3 netns $$PID2; \
		ETH_ID=0; \
		while sudo nsenter -t $$PID1 -n ip link show eth$$ETH_ID >/dev/null 2>&1; do ETH_ID=$$((ETH_ID+1)); done; \
		IFACE1="eth$$ETH_ID"; \
		sudo nsenter -t $$PID1 -n ip link set $$VETH1 name $$IFACE1; \
		sudo nsenter -t $$PID1 -n ip link set $$IFACE1 up; \
		for ip in $$(echo $$ip1 | tr ',' ' '); do sudo nsenter -t $$PID1 -n ip addr add $$ip dev $$IFACE1; done; \
		ETH_ID=0; \
		while sudo nsenter -t $$PID2 -n ip link show eth$$ETH_ID >/dev/null 2>&1; do ETH_ID=$$((ETH_ID+1)); done; \
		IFACE2="eth$$ETH_ID"; \
		sudo nsenter -t $$PID2 -n ip link set $$VETH3 name $$IFACE2; \
		sudo nsenter -t $$PID2 -n ip link set $$IFACE2 up; \
		for ip in $$(echo $$ip2 | tr ',' ' '); do sudo nsenter -t $$PID2 -n ip addr add $$ip dev $$IFACE2; done; \
		echo "Connected $$c1 ($$IFACE1) <-> $$c2 ($$IFACE2)"; \
	done

# Set default routes
routes:
	@for mapping in $(CONTAINER_DEFAULT_IF); do \
		container=$${mapping%%:*}; \
		target_ip=$${mapping#*:}; \
		echo "Setting default route for $$container via interface with IP $$target_ip..."; \
		PID=$$(docker inspect -f '{{.State.Pid}}' $$container); \
		if [ -z "$$PID" ] || [ "$$PID" = "0" ]; then \
			echo "Error: $$container is not running."; \
			continue; \
		fi; \
		IFACE=$$(sudo nsenter -t $$PID -n ip -o addr show | grep "$$target_ip" | awk '{print $$2}'); \
		if [ -z "$$IFACE" ]; then \
			echo "Error: Could not find interface with IP $$target_ip in $$container"; \
			continue; \
		fi; \
		sudo nsenter -t $$PID -n ip route del default 2>/dev/null || true; \
		sudo nsenter -t $$PID -n ip route add default dev $$IFACE; \
		echo "Set default route for $$container dev $$IFACE"; \
	done
	@for mapping in $(CONTAINER_DEFAULT_GW); do \
		container=$${mapping%%:*}; \
		gw_ips=$${mapping#*:}; \
		echo "Setting default routes for $$container via $$gw_ips..."; \
		PID=$$(docker inspect -f '{{.State.Pid}}' $$container); \
		if [ -z "$$PID" ] || [ "$$PID" = "0" ]; then continue; fi; \
		sudo nsenter -t $$PID -n ip route del default 2>/dev/null || true; \
		sudo nsenter -t $$PID -n ip -6 route del default 2>/dev/null || true; \
		for gw_ip in $$(echo $$gw_ips | tr ',' ' '); do \
			if echo $$gw_ip | grep -q ':'; then \
				sudo nsenter -t $$PID -n ip -6 route add default via $$gw_ip 2>/dev/null || true; \
				echo "Set IPv6 default route for $$container via $$gw_ip"; \
			else \
				sudo nsenter -t $$PID -n ip route add default via $$gw_ip 2>/dev/null || true; \
				echo "Set IPv4 default route for $$container via $$gw_ip"; \
			fi; \
		done; \
	done

# Set up ARP entries for all containers
arp-setup:
	@# Extract IP and MAC mappings from CONTAINER_TO_BRIDGE
	@for mapping in $(CONTAINER_TO_BRIDGE); do \
		container=$${mapping%%@*}; \
		rest=$${mapping#*@}; \
		bridge=$${rest%%@*}; \
		rest=$${rest#*@}; \
		ip_addr=$${rest%%@*}; \
		rest=$${rest#*@}; \
		veth_host=$${rest%%@*}; \
		mac_addr=$${rest#*@}; \
		if [ "$$ip_addr" != "0" ]; then \
			for ip in $$(echo $$ip_addr | tr ',' ' '); do \
				clean_ip=$${ip%%/*}; \
				if echo $$clean_ip | grep -q ':'; then \
					echo "Processing IPv6: $$clean_ip -> $$mac_addr for $$container"; \
				else \
					echo "Processing IPv4: $$clean_ip -> $$mac_addr for $$container"; \
				fi; \
				for target_container in $(CONTAINERS); do \
					if [ "$$target_container" != "$$container" ] && [ "$$target_container" != "ONOS" ]; then \
						PID=$$(docker inspect -f '{{.State.Pid}}' $$target_container 2>/dev/null); \
						if [ -n "$$PID" ] && [ "$$PID" != "0" ]; then \
							if echo $$clean_ip | grep -q ':'; then \
								sudo nsenter -t $$PID -n ip -6 neigh add $$clean_ip lladdr $$mac_addr dev eth0 2>/dev/null || \
								sudo nsenter -t $$PID -n ip -6 neigh replace $$clean_ip lladdr $$mac_addr dev eth0 2>/dev/null || true; \
								echo "Added IPv6 ARP entry in $$target_container: $$clean_ip -> $$mac_addr"; \
							else \
								sudo nsenter -t $$PID -n arp -s $$clean_ip $$mac_addr 2>/dev/null || \
								sudo nsenter -t $$PID -n ip neigh add $$clean_ip lladdr $$mac_addr dev eth0 2>/dev/null || \
								sudo nsenter -t $$PID -n ip neigh replace $$clean_ip lladdr $$mac_addr dev eth0 2>/dev/null || true; \
								echo "Added IPv4 ARP entry in $$target_container: $$clean_ip -> $$mac_addr"; \
							fi; \
						fi; \
					fi; \
				done; \
			done; \
		fi; \
	done
	@# Extract IP and MAC mappings from CONTAINER_TO_CONTAINER 
	@for mapping in $(CONTAINER_TO_CONTAINER); do \
		c1=$${mapping%%@*}; \
		rest=$${mapping#*@}; \
		c2=$${rest%%@*}; \
		rest=$${rest#*@}; \
		ip1=$${rest%%@*}; \
		ip2=$${rest#*@}; \
		echo "Processing container-to-container mapping: $$c1 <-> $$c2"; \
		PID1=$$(docker inspect -f '{{.State.Pid}}' $$c1 2>/dev/null); \
		PID2=$$(docker inspect -f '{{.State.Pid}}' $$c2 2>/dev/null); \
		if [ -n "$$PID1" ] && [ "$$PID1" != "0" ] && [ -n "$$PID2" ] && [ "$$PID2" != "0" ]; then \
			for ip in $$(echo $$ip1 | tr ',' ' '); do \
				clean_ip=$${ip%%/*}; \
				MAC1=$$(sudo nsenter -t $$PID1 -n ip addr show | grep "$$clean_ip" -A1 | grep "link/ether" | awk '{print $$2}' | head -1); \
				if [ -n "$$MAC1" ]; then \
					for target_container in $(CONTAINERS); do \
						if [ "$$target_container" != "$$c1" ] && [ "$$target_container" != "ONOS" ]; then \
							TARGET_PID=$$(docker inspect -f '{{.State.Pid}}' $$target_container 2>/dev/null); \
							if [ -n "$$TARGET_PID" ] && [ "$$TARGET_PID" != "0" ]; then \
								if echo $$clean_ip | grep -q ':'; then \
									sudo nsenter -t $$TARGET_PID -n ip -6 neigh add $$clean_ip lladdr $$MAC1 dev eth0 2>/dev/null || \
									sudo nsenter -t $$TARGET_PID -n ip -6 neigh replace $$clean_ip lladdr $$MAC1 dev eth0 2>/dev/null || true; \
								else \
									sudo nsenter -t $$TARGET_PID -n arp -s $$clean_ip $$MAC1 2>/dev/null || \
									sudo nsenter -t $$TARGET_PID -n ip neigh add $$clean_ip lladdr $$MAC1 dev eth0 2>/dev/null || \
									sudo nsenter -t $$TARGET_PID -n ip neigh replace $$clean_ip lladdr $$MAC1 dev eth0 2>/dev/null || true; \
								fi; \
								echo "Added ARP entry in $$target_container: $$clean_ip -> $$MAC1 (from $$c1)"; \
							fi; \
						fi; \
					done; \
				fi; \
			done; \
			for ip in $$(echo $$ip2 | tr ',' ' '); do \
				clean_ip=$${ip%%/*}; \
				MAC2=$$(sudo nsenter -t $$PID2 -n ip addr show | grep "$$clean_ip" -A1 | grep "link/ether" | awk '{print $$2}' | head -1); \
				if [ -n "$$MAC2" ]; then \
					for target_container in $(CONTAINERS); do \
						if [ "$$target_container" != "$$c2" ] && [ "$$target_container" != "ONOS" ]; then \
							TARGET_PID=$$(docker inspect -f '{{.State.Pid}}' $$target_container 2>/dev/null); \
							if [ -n "$$TARGET_PID" ] && [ "$$TARGET_PID" != "0" ]; then \
								if echo $$clean_ip | grep -q ':'; then \
									sudo nsenter -t $$TARGET_PID -n ip -6 neigh add $$clean_ip lladdr $$MAC2 dev eth0 2>/dev/null || \
									sudo nsenter -t $$TARGET_PID -n ip -6 neigh replace $$clean_ip lladdr $$MAC2 dev eth0 2>/dev/null || true; \
								else \
									sudo nsenter -t $$TARGET_PID -n arp -s $$clean_ip $$MAC2 2>/dev/null || \
									sudo nsenter -t $$TARGET_PID -n ip neigh add $$clean_ip lladdr $$MAC2 dev eth0 2>/dev/null || \
									sudo nsenter -t $$TARGET_PID -n ip neigh replace $$clean_ip lladdr $$MAC2 dev eth0 2>/dev/null || true; \
								fi; \
								echo "Added ARP entry in $$target_container: $$clean_ip -> $$MAC2 (from $$c2)"; \
							fi; \
						fi; \
					done; \
				fi; \
			done; \
		fi; \
	done

# Copy files into containers
copy:
	@for container in $(CONTAINERS); do \
		echo "Copying $(FILE_TO_COPY) to $$container..."; \
		docker cp $(FILE_TO_COPY) $$container:$(DEST_DIR); \
	done

check:
	@for container in $(CONTAINERS); do \
		echo "Networking info for $$container:"; \
		PID=$$(docker inspect -f '{{.State.Pid}}' $$container); \
		if [ -z "$$PID" ] || [ "$$PID" = "0" ]; then \
			echo "Error: $$container is not running."; \
			continue; \
		fi; \
		sudo nsenter -t $$PID -n ip addr show; \
		sudo nsenter -t $$PID -n ip route show; \
		echo ""; \
	done

setup_onos:
	sleep 20
	# clean up ssh key.
	
	ssh-keygen -f "/home/alen/.ssh/known_hosts" -R "[192.168.100.2]:8101"; \

	# use onos-app to setup
	onos-app 192.168.100.2 activate org.onosproject.openflow
	onos-app 192.168.100.2 activate org.onosproject.fpm
	onos-app 192.168.100.2 activate org.onosproject.route-service
	sleep 5
	onos-app 192.168.100.2 install! proxyndp/target/proxyndp-1.0-SNAPSHOT.oar
	onos-app 192.168.100.2 install! interdomain/target/interdomain-1.0-SNAPSHOT.oar

	# upload configurations
	onos-netcfg 192.168.100.2 ./conf_t.json
# 	onos-netcfg 192.168.100.2 ./conf.json
# 	onos-app 192.168.100.2 activate org.onosproject.vrouter
	
	
config_frr:
	docker exec -it FRR bash -c "echo 'net.ipv4.ip_forward=1' > /etc/sysctl.conf"
	docker exec -it FRR bash -c "echo 'net.ipv6.conf.all.forwarding=1' >> /etc/sysctl.conf"
	docker exec -it FRR bash -c "echo 'net.ipv6.conf.all.disable_ipv6=0' >> /etc/sysctl.conf"

	docker exec -it FRR bash -c "sysctl -p"
	docker cp ./daemons FRR:/etc/frr/daemons
	docker cp ./frr.conf FRR:/etc/frr/frr.conf
	docker restart FRR

	docker exec -it R2 bash -c "echo 'net.ipv4.ip_forward=1' > /etc/sysctl.conf"
	docker exec -it R2 bash -c "echo 'net.ipv6.conf.all.forwarding=1' >> /etc/sysctl.conf"
	docker exec -it R2 bash -c "echo 'net.ipv6.conf.all.disable_ipv6=0' >> /etc/sysctl.conf"

	docker exec -it R2 bash -c "sysctl -p"
	docker cp ./daemons R2:/etc/frr/daemons
	docker cp ./R2.conf R2:/etc/frr/frr.conf
	docker restart R2

start_frr:
	docker exec -it FRR bash -c "service frr start"
	docker exec -it R2 bash -c "service frr start"

restart_frr:
	docker exec -it FRR bash -c "service frr restart"
	docker exec -it R2 bash -c "service frr restart"
