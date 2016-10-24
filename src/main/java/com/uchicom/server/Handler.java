/* (c) 2013 uchicom */
package com.uchicom.server;

import java.io.IOException;
import java.nio.channels.SelectionKey;

/**
 * ハンドラーインターフェース.
 *
 * @author uchicom: Shigeki Uchiyama
 *
 */
public interface Handler {

	public void handle(SelectionKey key) throws IOException;
}
