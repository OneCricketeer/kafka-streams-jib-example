# Containerize a [Kafka Streams](http://kafka.apache.org/documentation/streams) application with Jib

Run `./mvnw clean package` to build your container.

## Tutorial

The following tutorial for using Jib alongside Kafka will require installation of `docker-compose`, and uses the [Bitnami](https://github.com/bitnami/bitnami-docker-kafka) Kafka+Zookeeper images, however any other Kafka or ZooKeeper Docker images should work. 

## Without Docker

If not using Docker, Kafka and ZooKeeper can be started locally using their respective start scripts. If this is done, though, the the variables for the bootstrap servers will need to be adjusted accordingly.  

The following steps can be used to run this application locally outside of Docker after creating the input and output topic, and producing [`lipsum.txt`](lipsum.txt) into the input topic (copy the executed commands from below, and switch the real addresses of Kafka for `localhost`).  

```bash
export BOOTSTRAP_SERVERS=localhost:9092  # Assumes Kafka default port
export STREAMS_INPUT=streams-plaintext-input
export STREAMS_OUTPUT=streams-plaintext-output
export AUTO_OFFSET_RESET=earliest
./mvnw clean exec:java
```

### Start Kafka Cluster in Docker

> ***Note***: Sometimes the Kafka container kills itself in below steps, and the consumer commands therefore may need to be re-executed. The Streams Application should reconnect on its own. 

For this exercise, we will be using three separate termainal windows, so go ahead and open those. 

First, we start with getting our cluster running in the foreground. This starts Kafka listening on `29092` on the host, and `9092` within the Docker network. Zookeeper is available on `2181`.

> *Terminal 1*

```bash
docker-compose up zookeeper kafka
```

### Create Kafka Topics

We need to create the topics where data will be produced into. 

> *Terminal 2*

```bash
docker-compose exec kafka \
    bash -c "kafka-topics.sh --create --if-not-exists --zookeeper zookeeper:2181 --topic streams-plaintext-input --partitions=1 --replication-factor=1"
```

```bash
docker-compose exec kafka \
    bash -c "kafka-topics.sh --create --if-not-exists --zookeeper zookeeper:2181 --topic streams-plaintext-output --partitions=1 --replication-factor=1"
```

Verify topics exist

```bash
docker-compose exec kafka \
    bash -c "kafka-topics.sh --list --zookeeper zookeeper:2181"
```

### Produce Lorem Ipsum into input topic

> *Terminal 2*

```bash
docker-compose exec kafka \
    bash -c "cat /data/lipsum.txt | kafka-console-producer.sh --topic streams-plaintext-input --broker-list kafka:9092"
```

Verify that data is there (note: hard-coding `max-messages` to the number of lines of expected text)

```
docker-compose exec kafka \
    bash -c "kafka-console-consumer.sh --topic streams-plaintext-input --bootstrap-server kafka:9092 --from-beginning --max-messages=9"
```

### Start Console Consumer for output topic

This command will seem to hang, but since there is no data yet, this is expected. 

> *Terminal 2*

```bash
docker-compose exec kafka \
    bash -c "kafka-console-consumer.sh --topic streams-plaintext-output --bootstrap-server kafka:9092 --from-beginning"
```

### Start Kafka Streams Application

Now, we can start our application to read from the beginning of the input topic that had data sent into it, and begin processing it. 

> *Terminal 3*

```bash
docker-compose up kafka-streams
```

*You should begin to see output in Terminal 2*

<kbd>Ctrl+C</kbd> on ***terminal 2*** after successful output and should see `Processed a total of 509 messages` if all words produced and consumed exactly once.  

## Extra

Redo the tutorial with more input data and partitions, then play with `docker-compose scale` to add more Kafka Streams process in parallel.

#### Profiles 

The `exec:java` goal can be rerun or container rebuilt to perform completely different Kafka Streams pipelines using Maven Profiles. 

To rebuild the container, for example, run `mvn -Pprofile clean package`

|Profile|Container Name|Main Class|Description|
|---|---|---|---|
|`upper` (default)|`kafka-streams-jib-example`|[`ToUpperCaseProcessor`](src/main/java/example/kafkastreams/ToUpperCaseProcessor.java)|Takes text from input topic and and uppercases all the words to an output topic. (The example provided above.)|
|`consumer-offsets`|`kafka-streams-jib-example-2`|[`ConsumerOffsetsToJSONProcessor`](src/main/java/example/kafkastreams/ConsumerOffsetsToJSONProcessor.java)|Translates the `__consumer_offsets` topic in binary format out to JSON. It is a Java port of [this repo](https://github.com/sderosiaux/kafka-streams-consumer-offsets-to-json).| 

### Disclaimer

The examples given are mostly intended to show stateless processing. If doing stateful operations within Kafka Streams that maintain data on disk (e.g. in RocksDB), then you'll want a persistent storage layer for the application. This is not provided with Docker out of the box.  

If running the apps multiple times and expecting similar results, then you'll need to first use the [Kafka Streams Application Reset Tool](https://cwiki.apache.org/confluence/display/KAFKA/Kafka+Streams+Application+Reset+Tool) after first stopping the Docker/Streams instance(s).  

## Cleanup environment

```bash
docker-compose rm -sf
# Clean up mounted docker volumes
docker volume ls | grep $(basename `pwd`) | awk '{print $2}' | xargs docker volume rm 
# Clean up networks
docker network ls | grep $(basename `pwd`) | awk '{print $2}' | xargs docker network rm
```

## More information

Learn [more about Jib](https://github.com/GoogleContainerTools/jib).

Learn [more about Apache Kafka & Kafka Streams](http://kafka.apache.org/documentation).
