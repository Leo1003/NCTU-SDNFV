#!/usr/bin/bash
podman network create --driver bridge --subnet 172.1.0.0/16 --gateway 172.1.0.254 --internal R1_network
podman network create --driver bridge --subnet 172.2.0.0/16 --gateway 172.2.0.254 --internal R2_network
podman network create --driver bridge --subnet 172.3.0.0/16 --gateway 172.3.0.254 --internal R3_network
podman network create --driver bridge --subnet 172.4.0.0/16 --gateway 172.4.0.254 --internal R4_network
podman network create --driver bridge --subnet 172.0.1.0/24 --gateway 172.0.1.254 --ip-range 172.0.1.0/30 --internal R1R2_br
podman network create --driver bridge --subnet 172.0.2.0/24 --gateway 172.0.2.254 --ip-range 172.0.2.0/30 --internal R2R3_br
podman network create --driver bridge --subnet 172.0.3.0/24 --gateway 172.0.3.254 --ip-range 172.0.3.0/30 --internal R3R4_br
podman network create --driver bridge --subnet 172.0.4.0/24 --gateway 172.0.4.254 --ip-range 172.0.4.0/30 --internal R4R1_br

