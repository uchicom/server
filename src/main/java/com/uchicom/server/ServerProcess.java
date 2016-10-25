// (c) 2016 uchicom
package com.uchicom.server;

public interface ServerProcess {

	public long getLastTime();
	public void forceClose();
	public void execute();
}
