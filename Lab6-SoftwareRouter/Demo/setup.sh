#!/usr/bin/bash
echo >&2 "[1/6] Starting router containers..."
podman run --network R1_network \
    --ip 172.21.0.1 \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    --cap-add SYS_ADMIN \
    -d \
    --name R1 \
    --mount type=bind,src=R1-bgpd.conf,dst=/etc/quagga/bgpd.conf,ro=true \
    --mount type=bind,src=R1-zebra.conf,dst=/etc/quagga/zebra.conf,ro=true \
    localhost/sdnfv-lab6-router

podman run --network R2_network \
    --ip 172.22.0.1 \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    --cap-add SYS_ADMIN \
    -d \
    --name R2 \
    --mount type=bind,src=R2-bgpd.conf,dst=/etc/quagga/bgpd.conf,ro=true \
    --mount type=bind,src=R2-zebra.conf,dst=/etc/quagga/zebra.conf,ro=true \
    localhost/sdnfv-lab6-router

podman run --network R3_network \
    --ip 172.23.0.1 \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    --cap-add SYS_ADMIN \
    -d \
    --name R3 \
    --mount type=bind,src=R3-bgpd.conf,dst=/etc/quagga/bgpd.conf,ro=true \
    --mount type=bind,src=R3-zebra.conf,dst=/etc/quagga/zebra.conf,ro=true \
    localhost/sdnfv-lab6-router

echo >&2 "[2/6] Connecting inter-router networks..."
podman network connect R1R2_br R1
podman network connect R1R2_br R2
podman network connect R2R3_br R2
podman network connect R2R3_br R3

echo >&2 "[3/6] Removing old router IPs and gateways..."
podman exec R1 ip route delete default via 172.21.0.254 2> /dev/null
podman exec R1 ip route delete default via 172.20.1.254 2> /dev/null
podman exec R1 ip addr delete 172.20.1.1/24 dev eth1 2> /dev/null
podman exec R1 ip addr delete 172.20.1.2/24 dev eth1 2> /dev/null
podman exec R2 ip route delete default via 172.22.0.254 2> /dev/null
podman exec R2 ip route delete default via 172.20.1.254 2> /dev/null
podman exec R2 ip route delete default via 172.20.2.254 2> /dev/null
podman exec R2 ip addr delete 172.20.1.1/24 dev eth1 2> /dev/null
podman exec R2 ip addr delete 172.20.1.2/24 dev eth1 2> /dev/null
podman exec R2 ip addr delete 172.20.2.1/24 dev eth2 2> /dev/null
podman exec R2 ip addr delete 172.20.2.2/24 dev eth2 2> /dev/null
podman exec R3 ip route delete default via 172.23.0.254 2> /dev/null
podman exec R3 ip route delete default via 172.20.2.254 2> /dev/null
podman exec R3 ip addr delete 172.20.2.1/24 dev eth1 2> /dev/null
podman exec R3 ip addr delete 172.20.2.2/24 dev eth1 2> /dev/null

echo >&2 "[4/6] Applying static router IPs..."
podman exec R1 ip addr add 172.20.1.1/24 dev eth1
podman exec R2 ip addr add 172.20.1.2/24 dev eth1
podman exec R2 ip addr add 172.20.2.1/24 dev eth2
podman exec R3 ip addr add 172.20.2.2/24 dev eth1

echo >&2 "[5/6] Start host containers..."
podman run --network R1_network \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    -d \
    --name h1 \
    localhost/sdnfv-lab6

podman run --network R2_network \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    -d \
    --name h2 \
    localhost/sdnfv-lab6

podman run --network R3_network \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    -d \
    --name h3 \
    localhost/sdnfv-lab6

echo >&2 "[6/6] Reconfiguring host gateway..."
podman exec h1 ip route delete default via 172.21.0.254
podman exec h1 ip route add default via 172.21.0.1
podman exec h2 ip route delete default via 172.22.0.254
podman exec h2 ip route add default via 172.22.0.1
podman exec h3 ip route delete default via 172.23.0.254
podman exec h3 ip route add default via 172.23.0.1

