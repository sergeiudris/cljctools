# ipfs
ipfs and libp2p libs

## why

- should be imlemented in cljc, for jvm first and foremost: jvm is key, networking is key, that's why we need the jvm implementaion to be the best
- there is no need - simply by design of p2p networking - for libp2p to be in the browser

## goal

- one protocol, one encryption,...  - pick one (like bittorrent) and focus on user programs, not swiss army knife bloated tooling (MULTICS vs UNIX all over again..)