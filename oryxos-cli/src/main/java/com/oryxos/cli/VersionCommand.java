package com.oryxos.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Print OryxOS version (no Spring context).
 */
@Command(name = "version", description = "Print OryxOS version")
public class VersionCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        OryxOsCli.printBanner(System.out);
        return 0;
    }
}
