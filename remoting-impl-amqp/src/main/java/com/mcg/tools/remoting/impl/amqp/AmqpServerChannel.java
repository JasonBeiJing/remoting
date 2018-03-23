package com.mcg.tools.remoting.impl.amqp;

import java.io.IOException;
import java.util.HashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;

import com.mcg.tools.remoting.common.ExportedService;
import com.mcg.tools.remoting.common.io.ServerChannel;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.AMQP.BasicProperties;

public class AmqpServerChannel implements ServerChannel, ConnectionListener {

	private static Log log = LogFactory.getLog(AmqpServerChannel.class);
	
	private Connection connection;
	private ConnectionFactory connectionFactory;
	private String app;
	private String service;
	private Channel serverChannel;
	private ExportedService exportedService;

	public AmqpServerChannel(ConnectionFactory connectionFactory, String app, String service, ExportedService exportedService) {
		this.connectionFactory = connectionFactory;
		this.connectionFactory.addConnectionListener(this);
		this.app = app;
		this.service = service;
		this.exportedService = exportedService;
		this.connection = connectionFactory.createConnection();
		listen(connection);
	}

	private void listen(Connection connection) {
		
		try {

			String exchangeName = app + ":" + service;
			String requestQueueName = app+":"+service+":request";
			
			this.serverChannel = connection.createChannel(false);
			this.serverChannel.exchangeDeclare(exchangeName, BuiltinExchangeType.DIRECT, true, false, new HashMap<>());
			this.serverChannel.queueDeclare(requestQueueName, false, false, false, new HashMap<>());
			this.serverChannel.queueBind(requestQueueName, exchangeName, "request");
			this.serverChannel.basicConsume(requestQueueName, false, new DefaultConsumer(this.serverChannel) {
				
				@Override
				public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
					try {

						byte[] responseBody = exportedService.handle(body);
						String correlationId = properties.getCorrelationId();
						String routingKey = properties.getReplyTo();
						BasicProperties props = new BasicProperties.Builder().correlationId(correlationId).build();
						long deliveryTag = envelope.getDeliveryTag();
						getChannel().basicAck(deliveryTag, false);

						getChannel().basicPublish(app+":"+service, routingKey, props, responseBody);
					} catch (Exception e) {
						throw new IOException(e);
					}
					
				}
			});
			
		} catch (Exception e) {
			log.warn("error creating consumer: ",e);
		}
		

	}
	
	@Override
	public void onCreate(Connection connection) {
		listen(connection);
	}

	@Override
	public void onClose(Connection connection) {
		this.connection = connectionFactory.createConnection();
		listen(this.connection);
	}
	
	
	
}
