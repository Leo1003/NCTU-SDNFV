#!/usr/bin/bash
podman run --network R1_network \
    --ip 172.1.0.1 \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    --cap-add SYS_ADMIN \
    -d \
    --name R1 \
    --mount type=bind,src=R1-bgpd.conf,dst=/etc/quagga/bgpd.conf,ro=true \
    --mount type=bind,src=R1-zebra.conf,dst=/etc/quagga/zebra.conf,ro=true \
    localhost/sdnfv-lab6-router

podman run --network R2_network \
    --ip 172.2.0.1 \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    --cap-add SYS_ADMIN \
    -d \
    --name R2 \
    --mount type=bind,src=R2-bgpd.conf,dst=/etc/quagga/bgpd.conf,ro=true \
    --mount type=bind,src=R2-zebra.conf,dst=/etc/quagga/zebra.conf,ro=true \
    localhost/sdnfv-lab6-router

podman run --network R3_network \
    --ip 172.3.0.1 \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    --cap-add SYS_ADMIN \
    -d \
    --name R3 \
    --mount type=bind,src=R3-bgpd.conf,dst=/etc/quagga/bgpd.conf,ro=true \
    --mount type=bind,src=R3-zebra.conf,dst=/etc/quagga/zebra.conf,ro=true \
    localhost/sdnfv-lab6-router

podman run --network R4_network \
    --ip 172.4.0.1 \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    --cap-add SYS_ADMIN \
    -d \
    --name R4 \
    --mount type=bind,src=R4-bgpd.conf,dst=/etc/quagga/bgpd.conf,ro=true \
    --mount type=bind,src=R4-zebra.conf,dst=/etc/quagga/zebra.conf,ro=true \
    localhost/sdnfv-lab6-router

podman network connect R1R2_br R1
podman network connect R4R1_br R1
podman network connect R1R2_br R2
podman network connect R2R3_br R2
podman network connect R2R3_br R3
podman network connect R3R4_br R3
podman network connect R3R4_br R4
podman network connect R4R1_br R4

podman exec R1 ip route delete default via 172.1.0.254
podman exec R1 ip route delete default via 172.0.1.254
podman exec R1 ip route delete default via 172.0.4.254
podman exec R1 ip addr delete 172.0.1.1/24 dev eth1
podman exec R1 ip addr delete 172.0.1.2/24 dev eth1
podman exec R1 ip addr delete 172.0.4.1/24 dev eth2
podman exec R1 ip addr delete 172.0.4.2/24 dev eth2
podman exec R2 ip route delete default via 172.2.0.254
podman exec R2 ip route delete default via 172.0.1.254
podman exec R2 ip route delete default via 172.0.2.254
podman exec R2 ip addr delete 172.0.1.1/24 dev eth1
podman exec R2 ip addr delete 172.0.1.2/24 dev eth1
podman exec R2 ip addr delete 172.0.2.1/24 dev eth2
podman exec R2 ip addr delete 172.0.2.2/24 dev eth2
podman exec R3 ip route delete default via 172.3.0.254
podman exec R3 ip route delete default via 172.0.2.254
podman exec R3 ip route delete default via 172.0.3.254
podman exec R3 ip addr delete 172.0.2.1/24 dev eth1
podman exec R3 ip addr delete 172.0.2.2/24 dev eth1
podman exec R3 ip addr delete 172.0.3.1/24 dev eth2
podman exec R3 ip addr delete 172.0.3.2/24 dev eth2
podman exec R4 ip route delete default via 172.4.0.254
podman exec R4 ip route delete default via 172.0.3.254
podman exec R4 ip route delete default via 172.0.4.254
podman exec R4 ip addr delete 172.0.3.1/24 dev eth1
podman exec R4 ip addr delete 172.0.3.2/24 dev eth1
podman exec R4 ip addr delete 172.0.4.1/24 dev eth2
podman exec R4 ip addr delete 172.0.4.2/24 dev eth2

podman exec R1 ip addr add 172.0.1.1/24 dev eth1
podman exec R1 ip addr add 172.0.4.1/24 dev eth2
podman exec R2 ip addr add 172.0.1.2/24 dev eth1
podman exec R2 ip addr add 172.0.2.1/24 dev eth2
podman exec R3 ip addr add 172.0.2.2/24 dev eth1
podman exec R3 ip addr add 172.0.3.1/24 dev eth2
podman exec R4 ip addr add 172.0.3.2/24 dev eth1
podman exec R4 ip addr add 172.0.4.2/24 dev eth2

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

podman run --network R4_network \
    --cap-add NET_ADMIN \
    --cap-add NET_RAW \
    -d \
    --name h4 \
    localhost/sdnfv-lab6

podman exec h1 ip route delete default via 172.1.0.254
podman exec h1 ip route add default via 172.1.0.1
podman exec h2 ip route delete default via 172.2.0.254
podman exec h2 ip route add default via 172.2.0.1
podman exec h3 ip route delete default via 172.3.0.254
podman exec h3 ip route add default via 172.3.0.1
podman exec h4 ip route delete default via 172.4.0.254
podman exec h4 ip route add default via 172.4.0.1

