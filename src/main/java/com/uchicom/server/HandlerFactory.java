/* (c) 2016 uchicom */
package com.uchicom.server;

public abstract class HandlerFactory {

	protected Parameter parameter;
	public HandlerFactory(Parameter parameter) {
		this.parameter = parameter;
	}
	/**
	 * ハンドラークラスを作成します.
	 * @return
	 */
	public abstract Handler createHandler();

	public static HandlerFactory createHandlerFactory(Parameter parameter) throws Exception {
		String className = parameter.get("handlerFactory");
		return (HandlerFactory) Class.forName(className).getConstructor(Parameter.class).newInstance(parameter);
	}
}
