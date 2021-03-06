package com.mcg.tools.remoting.common;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.mcg.tools.remoting.api.RemotingCodec;
import com.mcg.tools.remoting.api.RemotingInterceptor;
import com.mcg.tools.remoting.api.entities.RemotingRequest;
import com.mcg.tools.remoting.api.entities.RemotingResponse;
import com.mcg.tools.remoting.common.io.ClientChannel;

public class ImportedService<T> implements InvocationHandler {

	private static Log log = LogFactory.getLog(ImportedService.class);
	
	private ObjectMapper mapper = new ObjectMapper();
	
	private ClientChannel clientChannel;
	private Class<T> serviceInterface;
	private T proxy;
	private List<RemotingInterceptor> remotingInterceptors;
	private RemotingCodec remotingCodec;
	private Executor executor;
	
	public ImportedService(Class<T> serviceInterface, ClientChannel cc, List<RemotingInterceptor> remotingInterceptors, RemotingCodec remotingCodec, Executor executor) {
		this.clientChannel = cc;
		this.serviceInterface = serviceInterface;
		this.remotingInterceptors = remotingInterceptors;
		this.remotingCodec = remotingCodec;
		this.executor = executor;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		
		RemotingRequest request = remotingCodec.encodeRequest(method, args);
		if(remotingInterceptors!=null) {
			for(RemotingInterceptor ri : remotingInterceptors) {
				ri.beforeSend(request);
			}
		}

		byte[] in = mapper.writeValueAsBytes(request);
		
		InvokeCallable ic = new InvokeCallable(in);

		FutureTask<RemotingResponse> t = new FutureTask<RemotingResponse>(ic);
		executor.execute(t);
		
		RemotingResponse response = t.get(10, TimeUnit.SECONDS);
		
		if(remotingInterceptors!=null) {
			for(RemotingInterceptor ri : remotingInterceptors) {
				ri.afterReceive(request, response);
			}
		}
		if(!response.isSuccess()) throw new RuntimeException("REMOTE_CALL_FAILED");
		if(response.getReturnValue()==null) return null;
		return mapper.readValue(mapper.writeValueAsBytes(response.getReturnValue()), TypeFactory.defaultInstance().constructType(method.getGenericReturnType()));
	}
	
	
	@SuppressWarnings("unchecked")
	public T getProxy() {
		if(proxy==null) {
			log.info(" ======================================================================== ");
			log.info(" === ");
			log.info(" === CREATING PROXY FOR: "+serviceInterface);
			log.info(" === ");
			log.info(" ======================================================================== ");
			
			proxy = (T) Proxy.newProxyInstance(serviceInterface.getClassLoader(), new Class[] {serviceInterface}, this );
		}
		return proxy;
	}
	
	private class InvokeCallable implements Callable<RemotingResponse> {
		
		private byte[] in;
		
		public InvokeCallable(byte[] in) {
			this.in = in;
		}

		@Override
		public RemotingResponse call() throws Exception {
			byte[] out = clientChannel.invoke(in);
			return mapper.readValue(out, RemotingResponse.class);		
		}
	};
	

	
	
}
