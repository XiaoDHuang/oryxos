package com.oryxos.cli;

import picocli.CommandLine.Command;

/**
 * {@code profile} 父命令,挂载 list/show/create/delete 四个子命令.
 *
 * @author OryxOS Contributors
 */
@Command(
    name = "profile",
    description = "Profile 管理:list / show / create / delete",
    subcommands = {
      ProfileListCommand.class,
      ProfileShowCommand.class,
      ProfileCreateCommand.class,
      ProfileDeleteCommand.class
    })
public class ProfileCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }
}
