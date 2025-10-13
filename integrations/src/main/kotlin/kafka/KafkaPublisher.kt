package kafka

import events.DomainEvent
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.Properties

class KafkaPublisher(brokers: String) : AutoCloseable {
    private val producer: KafkaProducer<String, String>
    init {
        val props = Properties().apply {
            put("bootstrap.servers", brokers)
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            put("acks", "all")
            put("linger.ms", "5")
        }
        producer = KafkaProducer(props)
    }
    fun publish(topic: String, key: String, value: String) {
        producer.send(ProducerRecord(topic, key, value))
    }
    override fun close() {
        producer.flush()
        producer.close()
    }
}
