http:
  pprof:
    port: 6060
    enabled: false
  address: 0.0.0.0:3000
  session_ttl: 720h
users:
  - name: admin
    password: __BCRYPT__
auth_attempts: 5
block_auth_min: 15
language: uk
theme: auto
dns:
  bind_hosts:
    - 0.0.0.0
  port: 53
  anonymize_client_ip: true
  ratelimit: 0
  upstream_dns:
    - https://dns.quad9.net/dns-query
    - https://cloudflare-dns.com/dns-query
  upstream_mode: parallel
  bootstrap_dns:
    - 9.9.9.9
    - 1.1.1.1
  fallback_dns:
    - tls://dns.quad9.net
  protection_enabled: true
  blocking_mode: default
  cache_enabled: true
  cache_size: 4194304
  cache_optimistic: true
  enable_dnssec: true
  aaaa_disabled: false
  edns_client_subnet:
    enabled: false
  safebrowsing_enabled: true
  parental_enabled: false
  safesearch:
    enabled: false
  use_private_ptr_resolvers: false
  resolve_clients: false
querylog:
  enabled: false
  file_enabled: false
  interval: 24h
  size_memory: 1000
statistics:
  enabled: false
  interval: 168h
filters:
  - enabled: true
    url: https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt
    name: AdGuard DNS filter
    id: 1
  - enabled: true
    url: https://adguardteam.github.io/HostlistsRegistry/assets/filter_27.txt
    name: OISD Blocklist Big
    id: 2
  - enabled: true
    url: https://adguardteam.github.io/HostlistsRegistry/assets/filter_3.txt
    name: Peter Lowe's Blocklist
    id: 3
  - enabled: true
    url: https://adguardteam.github.io/HostlistsRegistry/assets/filter_9.txt
    name: The Big List of Hacked Malware Web Sites
    id: 4
  - enabled: true
    url: https://adguardteam.github.io/HostlistsRegistry/assets/filter_30.txt
    name: Phishing Army
    id: 5
filtering:
  filtering_enabled: true
  filters_update_interval: 12
  protection_enabled: true
  rewrites: []
schema_version: 29
