docker exec -it coinflow-consumer-local sh -c 'apt-get update -y && apt-get install redis-tools -y && redis-cli -h host.docker.internal keys "ohlc:live:*"'
