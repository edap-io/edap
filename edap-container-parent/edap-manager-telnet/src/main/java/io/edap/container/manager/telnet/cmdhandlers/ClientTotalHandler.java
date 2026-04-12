package io.edap.container.manager.telnet.cmdhandlers;

import io.edap.Server;
import io.edap.ServerGroup;
import io.edap.nio.IoSelectorManager;
import io.edap.protocol.telnet.ShellCmdHandler;
import io.edap.protocol.telnet.ShellCommand;
import io.edap.protocol.telnet.TelnetServerNioSession;
import io.edap.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ClientTotalHandler implements ShellCmdHandler {
    @Override
    public void process(ShellCommand command, TelnetServerNioSession telnetSession) {
        Map<String, ServerGroup> serverGroups = telnetSession.getEdap().getServerGroups();

        Map<String, ServerGroup> sgs;
        boolean showVerbose = false;
        int refreshSecond = 5;
        if (command.getArgs() == null || command.getArgs().length == 0) {
            sgs = serverGroups;
        } else if (command.getArgs().length > 0) {
            String[] args;
            List<String> argList = new ArrayList<>();
            for (String arg : command.getArgs()) {
                if ("-v".equals(arg)) {
                    showVerbose = true;
                } else {
                    argList.add(arg);
                }
            }
            if (showVerbose) {
                args = argList.toArray(new String[0]);
            } else {
                args = command.getArgs();
            }
            if (args.length > 0) {
                if (command.getArgs()[0].charAt(0) > '0' && command.getArgs()[0].charAt(0) <= '9') {
                    sgs = serverGroups;
                    try {
                        refreshSecond = Integer.parseInt(command.getArgs()[0]);
                    } catch (NumberFormatException e) {
                        refreshSecond = 5;
                    }
                } else {
                    String[] sgNames = command.getArgs()[0].split(",");
                    sgs = new HashMap<>();
                    for (String name : sgNames) {
                        ServerGroup sg = serverGroups.get(name);
                        if (sg != null) {
                            sgs.put(sg.getName(), sg);
                        }
                    }
                }
            } else {
                sgs = serverGroups;
            }
        } else {
            sgs = new HashMap<>();
        }
        CtInfo ctInfo = buildCtHeader(sgs, showVerbose);
        StringBuilder result = ctInfo.stringBuilder;
        int maxSgNameLen     = ctInfo.maxSgNameLen;
        int maxListenHostLen = ctInfo.maxListenHostLen;
        int maxListenPortLen = ctInfo.maxListenPortLen;
        int maxServerNameLen = ctInfo.maxServerNameLen;
        int maxTotalLen      = ctInfo.maxTotalLen;
        int maxThreadNameLen = ctInfo.maxThreadNameLen;
        int maxThreadStatusLen = ctInfo.maxThreadStatusLen;

        int padding          = ctInfo.padding;

        telnetSession.writeString(result.toString());
        StringBuilder sep = new StringBuilder();
        for (int i=0;i<ctInfo.rowLen;i++) {
            sep.append('-');
        }
        sep.append('\n');
        String header = result.toString();
        String sepStr = sep.toString();
        AtomicReference<Integer> row = new AtomicReference<>(0);
        boolean showVerboseFlag = showVerbose;
        ScheduledExecutorService executorService = telnetSession.getScheduledExecutorService();
        telnetSession.setScheduledFuture(executorService.scheduleAtFixedRate(() -> {

                result.delete(0, result.length());
                if (row.get() > 30) {
                    row.getAndSet(0);
                    result.append('\n').append(header);
                }
                result.append("\033[2J");   // 刷屏
                result.append("\033[1;1H"); // 移动光标
                result.append(header);
                result.append(sepStr);
                row.getAndSet(row.get() + 1);
                for (Map.Entry<String, ServerGroup> entry : sgs.entrySet()) {
                    ServerGroup sg = entry.getValue();
                    for (Server server : sg.getServers()) {
                        Map<Server.Addr, IoSelectorManager> ioSelectorManagerMap = server.getIoSelectorManagerMap();
                        for (Map.Entry<Server.Addr, IoSelectorManager> addrIoManager : ioSelectorManagerMap.entrySet()) {
                            Server.Addr addr = addrIoManager.getKey();
                            if (showVerboseFlag) {
                                List<IoSelectorManager.IoWorkerInfo> ioWorkerInfos = addrIoManager.getValue().getWorkerInfoList();
                                for (IoSelectorManager.IoWorkerInfo info : ioWorkerInfos) {
                                    fillRightSpace(result, entry.getKey(), maxSgNameLen + padding);
                                    fillRightSpace(result, server.name(), maxServerNameLen + padding);
                                    fillLeftSpace(result, addr.host + ":", maxListenHostLen + 1);
                                    fillRightSpace(result, "" + addr.port, maxListenPortLen + padding);
                                    fillRightSpace(result, info.getThreadName(), maxThreadNameLen + padding);
                                    fillRightSpace(result, info.getWorkerStatus() + "", maxThreadStatusLen + padding);
                                    fillLeftSpace(result, info.getClientCount() + "", maxTotalLen);
                                    result.append('\n');
                                    row.getAndSet(row.get() + 1);
                                }
                            } else {
                                fillRightSpace(result, entry.getKey(), maxSgNameLen + padding);
                                fillRightSpace(result, server.name(), maxServerNameLen + padding);
                                fillLeftSpace (result, addr.host + ":", maxListenHostLen + 1);
                                fillRightSpace(result, "" + addr.port, maxListenPortLen + padding);
                                fillLeftSpace(result, addrIoManager.getValue().getClientCount() + "", maxTotalLen);
                                result.append('\n');
                                row.getAndSet(row.get() + 1);
                            }
                        }
                    }
                }
                telnetSession.writeString(result.toString());
            }, 1, refreshSecond, TimeUnit.SECONDS));
    }

    private CtInfo buildCtHeader(Map<String, ServerGroup> serverGroups, boolean showVerbose) {
        int maxSgNameLen     = "ServerGroup".length();
        int maxListenHostLen = "Host".length();
        int maxListenPortLen = "port".length();
        int maxServerNameLen = "ServerName".length();
        int maxTotalLen      = 11;
        int padding          = 4;
        int maxThreadNameLen = "threadName".length();
        int maxThreadStatusLen = "running".length();
        for (Map.Entry<String, ServerGroup> entry : serverGroups.entrySet()) {
            if (entry.getKey().length() > maxSgNameLen) {
                maxSgNameLen = entry.getKey().length();
            }
            ServerGroup sg = entry.getValue();
            ListenHostPortLen lhpl = getMaxListenLen(sg);
            if (lhpl.hostLen > maxListenHostLen) {
                maxListenHostLen = lhpl.hostLen;
            }
            if (lhpl.portLen > maxListenPortLen) {
                maxListenPortLen = lhpl.portLen;
            }
            if (lhpl.nameLen > maxServerNameLen) {
                maxServerNameLen = lhpl.nameLen;
            }

            if (showVerbose) {
                ThreadStatusInfoLen threadStatusInfoLen = getMaxThreadStatusInfoLen(sg);
                maxThreadNameLen = threadStatusInfoLen.getThreadNameLen();
                maxThreadStatusLen = threadStatusInfoLen.getThreadStatusLen();
            }
        }
        StringBuilder result = new StringBuilder();
        fillRightSpace(result, "ServerGroup", maxSgNameLen + padding);
        fillRightSpace(result, "Server", maxServerNameLen + padding);
        fillRightSpace(result, "Listen", maxListenHostLen + maxListenPortLen + padding + 1);
        if (showVerbose) {
            fillRightSpace(result, "threadName", maxTotalLen + padding);
            fillRightSpace(result, "threadStatus", maxTotalLen + padding);
            fillRightSpace(result, "ClientCount", maxTotalLen);
        } else {
            fillRightSpace(result, "ClientCount", maxTotalLen);
        }
        result.append('\n');
        int rowLen = result.length() - 1;


        CtInfo ctInfo = new CtInfo();
        ctInfo.setStringBuilder(result);
        ctInfo.setPadding(padding);
        ctInfo.setMaxListenHostLen(maxListenHostLen);
        ctInfo.setMaxListenPortLen(maxListenPortLen);
        ctInfo.setMaxServerNameLen(maxServerNameLen);
        ctInfo.setMaxTotalLen(maxTotalLen);
        ctInfo.setMaxSgNameLen(maxSgNameLen);
        ctInfo.setRowLen(rowLen);
        ctInfo.setMaxThreadNameLen(maxThreadNameLen);
        ctInfo.setMaxThreadStatusLen(maxThreadStatusLen);
        return ctInfo;
    }

    private ThreadStatusInfoLen getMaxThreadStatusInfoLen(ServerGroup sg) {
        List<Server> servers = sg.getServers();
        ThreadStatusInfoLen tsil = new ThreadStatusInfoLen();
        if (CollectionUtils.isEmpty(servers)) {
            return tsil;
        }
        int maxNameLen = 0;
        int maxStatusLen = "running".length();
        for (Server server : servers) {
            Map<Server.Addr, IoSelectorManager> ioSelectorManagerMap = server.getIoSelectorManagerMap();
            for (Map.Entry<Server.Addr, IoSelectorManager> entry : ioSelectorManagerMap.entrySet()) {
                Server.Addr addr = entry.getKey();
                IoSelectorManager ioSelectorManager = entry.getValue();
                List<IoSelectorManager.IoWorkerInfo> ioWorkerInfos = ioSelectorManager.getWorkerInfoList();
                for (IoSelectorManager.IoWorkerInfo info : ioWorkerInfos) {
                    if (info.getThreadName().length() > maxNameLen) {
                        maxNameLen = info.getThreadName().length();
                    }
                }
            }
        }

        tsil.setThreadNameLen(maxNameLen);
        tsil.setThreadStatusLen(maxStatusLen);
        return tsil;
    }

    private ListenHostPortLen getMaxListenLen(ServerGroup sg) {
        List<Server> servers = sg.getServers();
        ListenHostPortLen lhpl = new ListenHostPortLen();
        if (CollectionUtils.isEmpty(servers)) {
            return lhpl;
        }
        int maxHostLen = 0;
        int maxPortLen = 0;
        int maxNameLen = 0;
        for (Server server : servers) {
            List<Server.Addr> addrs = server.getListenAddrs();
            for (Server.Addr addr : addrs) {
                if (addr.host.length() > maxHostLen) {
                    maxHostLen = addr.host.length();
                }
                if (Integer.toString(addr.port).length() > maxPortLen) {
                    maxPortLen = Integer.toString(addr.port).length();
                }
                if (server.name().length() > maxNameLen) {
                    maxNameLen = server.name().length();
                }
            }
        }
        lhpl.hostLen = maxHostLen;
        lhpl.portLen = maxPortLen;
        lhpl.nameLen = maxNameLen;

        return lhpl;
    }

    private void fillLeftSpace(StringBuilder builder, String text, int len) {
        int left = len - text.length();
        for (int i=0;i<left;i++) {
            builder.append(' ');
        }
        builder.append(text);
    }

    private void fillRightSpace(StringBuilder builder, String text, int len) {
        int left = len - text.length();
        builder.append(text);
        for (int i=0;i<left;i++) {
            builder.append(' ');
        }

    }

    class CtInfo {
        private StringBuilder stringBuilder;
        private int maxSgNameLen;
        private int maxListenHostLen;
        private int maxListenPortLen;
        private int maxServerNameLen;
        private int maxTotalLen;
        private int maxThreadNameLen;
        private int maxThreadStatusLen;
        /**
         * 两列数据的间隔
         */
        private int padding;
        private int rowLen;

        public StringBuilder getStringBuilder() {
            return stringBuilder;
        }

        public void setStringBuilder(StringBuilder stringBuilder) {
            this.stringBuilder = stringBuilder;
        }

        public int getMaxSgNameLen() {
            return maxSgNameLen;
        }

        public void setMaxSgNameLen(int maxSgNameLen) {
            this.maxSgNameLen = maxSgNameLen;
        }

        public int getMaxListenHostLen() {
            return maxListenHostLen;
        }

        public void setMaxListenHostLen(int maxListenHostLen) {
            this.maxListenHostLen = maxListenHostLen;
        }

        public int getMaxListenPortLen() {
            return maxListenPortLen;
        }

        public void setMaxListenPortLen(int maxListenPortLen) {
            this.maxListenPortLen = maxListenPortLen;
        }

        public int getMaxServerNameLen() {
            return maxServerNameLen;
        }

        public void setMaxServerNameLen(int maxServerNameLen) {
            this.maxServerNameLen = maxServerNameLen;
        }

        public int getMaxTotalLen() {
            return maxTotalLen;
        }

        public void setMaxTotalLen(int maxTotalLen) {
            this.maxTotalLen = maxTotalLen;
        }

        /**
         * 两列数据的间隔
         */
        public int getPadding() {
            return padding;
        }

        public void setPadding(int padding) {
            this.padding = padding;
        }

        public int getRowLen() {
            return rowLen;
        }

        public void setRowLen(int rowLen) {
            this.rowLen = rowLen;
        }

        public int getMaxThreadNameLen() {
            return maxThreadNameLen;
        }

        public void setMaxThreadNameLen(int maxThreadNameLen) {
            this.maxThreadNameLen = maxThreadNameLen;
        }

        public int getMaxThreadStatusLen() {
            return maxThreadStatusLen;
        }

        public void setMaxThreadStatusLen(int maxThreadStatusLen) {
            this.maxThreadStatusLen = maxThreadStatusLen;
        }
    }

    class ThreadStatusInfoLen {
        private int threadNameLen;
        private int threadStatusLen;

        public int getThreadNameLen() {
            return threadNameLen;
        }

        public void setThreadNameLen(int threadNameLen) {
            this.threadNameLen = threadNameLen;
        }

        public int getThreadStatusLen() {
            return threadStatusLen;
        }

        public void setThreadStatusLen(int threadStatusLen) {
            this.threadStatusLen = threadStatusLen;
        }
    }

    class ListenHostPortLen {
        private int hostLen;
        private int portLen;
        private int nameLen;

        public int getHostLen() {
            return hostLen;
        }

        public void setHostLen(int hostLen) {
            this.hostLen = hostLen;
        }

        public int getPortLen() {
            return portLen;
        }

        public void setPortLen(int portLen) {
            this.portLen = portLen;
        }

        public int getNameLen() {
            return nameLen;
        }

        public void setNameLen(int nameLen) {
            this.nameLen = nameLen;
        }
    }
}
