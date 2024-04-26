package pro.kirillorlov.jib;

import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class Configuration {
    public List<LayerFilter> filters = new ArrayList<>();

    public static class LayerFilter {
        public String fileGlob = "";
        public String gavcGlob = "";
        public String layer = "";

        public void throwIfInvalid() throws JibPluginExtensionException {
            if (fileGlob.isEmpty() && gavcGlob.isEmpty()) {
                throw new JibPluginExtensionException(JibLayerFilterExtension.class, "either file glob or maven gavc glob should be specified");
            }
        }

        public PathMatcher globMatcher() {
            if (fileGlob.isEmpty())
                return null;
            return FileSystems.getDefault().getPathMatcher("glob:" + fileGlob);
        }

        public DefaultArtifact gavcArtifact() {
            if (gavcGlob.isEmpty())
                return null;
            return new DefaultArtifact(gavcGlob);
        }

        public boolean matches(Artifact artifact, Path pathInContainer) {
            boolean result = true;
            PathMatcher pathMatcher = globMatcher();
            if (pathMatcher != null) {
                result &= pathMatcher.matches(pathInContainer);
            }

            DefaultArtifact gavcPattern = gavcArtifact();
            if (gavcPattern != null) {
                if (artifact == null) {
                    return false;
                }
                result &= matches(gavcPattern.getGroupId(), artifact.getGroupId()) &&
                        matches(gavcPattern.getArtifactId(), artifact.getArtifactId()) &&
                        matches(gavcPattern.getVersion(), artifact.getVersion());
            }
            return result;
        }

        private boolean matches(String pattern, String value) {
            if (pattern.isEmpty())
                return true;

            return wildcardMatch(value, pattern);
        }

        static boolean wildcardMatch(String filename, String wildcardMatcher) {
            if (filename == null && wildcardMatcher == null) {
                return true;
            } else if (filename != null && wildcardMatcher != null) {
                String[] wcs = splitOnTokens(wildcardMatcher);
                boolean anyChars = false;
                int textIdx = 0;
                int wcsIdx = 0;
                Stack<int[]> backtrack = new Stack<>();

                do {
                    if (!backtrack.isEmpty()) {
                        int[] array = backtrack.pop();
                        wcsIdx = array[0];
                        textIdx = array[1];
                        anyChars = true;
                    }

                    for (; wcsIdx < wcs.length; ++wcsIdx) {
                        if (wcs[wcsIdx].equals("?")) {
                            ++textIdx;
                            if (textIdx > filename.length()) {
                                break;
                            }

                            anyChars = false;
                        } else if (wcs[wcsIdx].equals("*")) {
                            anyChars = true;
                            if (wcsIdx == wcs.length - 1) {
                                textIdx = filename.length();
                            }
                        } else {
                            if (anyChars) {
                                textIdx = checkIndexOf(filename, textIdx, wcs[wcsIdx]);
                                if (textIdx == -1) {
                                    break;
                                }

                                int repeat = checkIndexOf(filename, textIdx + 1, wcs[wcsIdx]);
                                if (repeat >= 0) {
                                    backtrack.push(new int[]{wcsIdx, repeat});
                                }
                            } else if (!filename.regionMatches(true, textIdx, wcs[wcsIdx], 0, wcs[wcsIdx].length())) {
                                break;
                            }

                            textIdx += wcs[wcsIdx].length();
                            anyChars = false;
                        }
                    }

                    if (wcsIdx == wcs.length && textIdx == filename.length()) {
                        return true;
                    }
                } while (!backtrack.isEmpty());

                return false;
            } else {
                return false;
            }
        }

        static int checkIndexOf(String str, int strStartIndex, String search) {
            int endIndex = str.length() - search.length();
            if (endIndex >= strStartIndex) {
                for (int i = strStartIndex; i <= endIndex; ++i) {
                    if (str.regionMatches(true, i, search, 0, search.length())) {
                        return i;
                    }
                }
            }

            return -1;
        }

        static String[] splitOnTokens(String text) {
            if (text.indexOf(63) == -1 && text.indexOf(42) == -1) {
                return new String[]{text};
            } else {
                char[] array = text.toCharArray();
                ArrayList<String> list = new ArrayList<>();
                StringBuilder buffer = new StringBuilder();
                char prevChar = 0;
                int len$ = array.length;

                for (int i$ = 0; i$ < len$; ++i$) {
                    char ch = array[i$];
                    if (ch != '?' && ch != '*') {
                        buffer.append(ch);
                    } else {
                        if (buffer.length() != 0) {
                            list.add(buffer.toString());
                            buffer.setLength(0);
                        }

                        if (ch == '?') {
                            list.add("?");
                        } else if (prevChar != '*') {
                            list.add("*");
                        }
                    }

                    prevChar = ch;
                }

                if (buffer.length() != 0) {
                    list.add(buffer.toString());
                }

                return list.toArray(new String[0]);
            }
        }
    }
}