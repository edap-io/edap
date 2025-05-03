package io.edap.container.manager.telnet.cmdhandlers;

import io.edap.Server;
import io.edap.ServerGroup;
import io.edap.protocol.telnet.ShellCmdHandler;
import io.edap.protocol.telnet.ShellCommand;
import io.edap.protocol.telnet.TelnetServerNioSession;
import io.edap.util.CollectionUtils;

import java.util.List;
import java.util.Map;

public class ClientTotalHandler implements ShellCmdHandler {
    @Override
    public void process(ShellCommand command, TelnetServerNioSession telnetServerNioSession) {
        Map<String, ServerGroup> serverGroups = telnetServerNioSession.getEdap().getServerGroups();
        int maxSgNameLen     = "ServerGroup".length();
        int maxListenHostLen = "Host".length();
        int maxListenPortLen = "port".length();
        int maxTotalLen      = 11;
        /**
         * 两列数据的间隔
         */
        int padding          = 4;
        if (command.getArgs().length == 0) {
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
            }
            StringBuilder result = new StringBuilder();
            fillRightSpace(result, "ServerGroup", maxSgNameLen + padding);
            fillRightSpace(result, "Listen", maxListenHostLen + maxListenPortLen + padding + 1);
            fillRightSpace(result, "ClientCount", maxTotalLen);
            result.append('\n');
            for (Map.Entry<String, ServerGroup> entry : serverGroups.entrySet()) {
                ServerGroup sg = entry.getValue();
                fillRightSpace(result, entry.getKey(), maxSgNameLen + padding);
            }
        } else if (command.getArgs().length == 1) {
            ServerGroup sg = serverGroups.get(command.getArgs()[0].trim());
            if (sg != null) {
                maxSgNameLen = sg.getName().length();
                ListenHostPortLen lhpl = getMaxListenLen(sg);
                if (lhpl.hostLen > maxListenHostLen) {
                    maxListenHostLen = lhpl.hostLen;
                }
                if (lhpl.portLen > maxListenPortLen) {
                    maxListenPortLen = lhpl.portLen;
                }
            }
        }
    }

    private ListenHostPortLen getMaxListenLen(ServerGroup sg) {
        List<Server> servers = sg.getServers();
        ListenHostPortLen lhpl = new ListenHostPortLen();
        if (CollectionUtils.isEmpty(servers)) {
            return lhpl;
        }
        int maxHostLen = 0;
        int maxPortLen = 0;
        for (Server server : servers) {
            List<Server.Addr> addrs = server.getListenAddrs();
            for (Server.Addr addr : addrs) {
                if (addr.host.length() > maxHostLen) {
                    maxHostLen = addr.host.length();
                }
                if (Integer.toString(addr.port).length() > maxPortLen) {
                    maxPortLen = Integer.toString(addr.port).length();
                }
            }
        }
        lhpl.hostLen = maxHostLen;
        lhpl.portLen = maxPortLen;

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

    class ListenHostPortLen {
        private int hostLen;
        private int portLen;

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
    }
}
