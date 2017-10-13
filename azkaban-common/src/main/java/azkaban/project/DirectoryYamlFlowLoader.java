/*
* Copyright 2017 LinkedIn Corp.
*
* Licensed under the Apache License, Version 2.0 (the “License”); you may not
* use this file except in compliance with the License. You may obtain a copy of
* the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an “AS IS” BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations under
* the License.
*/

package azkaban.project;

import azkaban.Constants;
import azkaban.flow.Edge;
import azkaban.flow.Flow;
import azkaban.flow.Node;
import azkaban.project.FlowLoaderUtils.SuffixFilter;
import azkaban.project.validator.ValidationReport;
import azkaban.utils.Props;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads yaml files to flows from project directory.
 */
public class DirectoryYamlFlowLoader implements FlowLoader {

  private static final Logger logger = LoggerFactory.getLogger(DirectoryYamlFlowLoader.class);

  private final Props props;
  private final Set<String> errors = new HashSet<>();
  private final Map<String, Flow> flowMap = new HashMap<>();
  private final Map<String, List<Edge>> edgeMap = new HashMap<>();

  /**
   * Creates a new DirectoryYamlFlowLoader.
   *
   * @param props Properties to add.
   */
  public DirectoryYamlFlowLoader(final Props props) {
    this.props = props;
  }

  /**
   * Returns the flow map constructed from the loaded flows.
   *
   * @return Map of flow name to Flow.
   */
  @Override
  public Map<String, Flow> getFlowMap() {
    return this.flowMap;
  }

  /**
   * Returns errors caught when loading flows.
   *
   * @return Set of error strings.
   */
  public Set<String> getErrors() {
    return this.errors;
  }

  /**
   * Returns the edge map constructed from the loaded flows.
   *
   * @return Map of flow name to all its Edges.
   */
  public Map<String, List<Edge>> getEdgeMap() {
    return this.edgeMap;
  }

  /**
   * Loads all project flows from the directory.
   *
   * @param project The project.
   * @param projectDir The directory to load flows from.
   * @return the validation report.
   */
  @Override
  public ValidationReport loadProjectFlow(final Project project, final File projectDir) {
    convertYamlFiles(projectDir);
    checkJobProperties(project);
    return FlowLoaderUtils.generateFlowLoaderReport(this.errors);
  }

  private void convertYamlFiles(final File projectDir) {
    // Todo jamiesjc: convert project yaml file.

    final File[] flowFiles = projectDir.listFiles(new SuffixFilter(Constants.FLOW_FILE_SUFFIX));
    for (final File file : flowFiles) {
      final NodeBeanLoader loader = new NodeBeanLoader();
      try {
        final NodeBean nodeBean = loader.load(file);
        final AzkabanFlow azkabanFlow = (AzkabanFlow) loader.toAzkabanNode(nodeBean);
        final Flow flow = convertAzkabanFlowToFlow(azkabanFlow);
        this.flowMap.put(flow.getId(), flow);
      } catch (final FileNotFoundException e) {
        logger.error("Error loading flow yaml files", e);
      }
    }
  }

  private Flow convertAzkabanFlowToFlow(final AzkabanFlow azkabanFlow) {
    final Flow flow = new Flow(azkabanFlow.getName());
    final Props props = azkabanFlow.getProps();
    FlowLoaderUtils.addEmailPropsToFlow(flow, props);

    // Convert azkabanNodes to nodes inside the flow.
    azkabanFlow.getNodes().values().stream().map(this::convertAzkabanNodeToNode)
        .forEach(n -> flow.addNode(n));

    // Add edges for the flow.
    buildFlowEdges(azkabanFlow);
    flow.addAllEdges(this.edgeMap.get(flow.getId()));

    // Todo jamiesjc: deprecate startNodes, endNodes and numLevels, and remove below method finally.
    // Blow method will construct startNodes, endNodes and numLevels for the flow.
    flow.initialize();

    return flow;
  }

  private Node convertAzkabanNodeToNode(final AzkabanNode azkabanNode) {
    final Node node = new Node(azkabanNode.getName());
    node.setType(azkabanNode.getType());

    if (azkabanNode.getType().equals(Constants.NODE_TYPE_FLOW)) {
      node.setEmbeddedFlowId(azkabanNode.getName());
      final Flow flow_node = convertAzkabanFlowToFlow((AzkabanFlow) azkabanNode);
      this.flowMap.put(flow_node.getId(), flow_node);
    }
    return node;
  }

  private void buildFlowEdges(final AzkabanFlow azkabanFlow) {
    // Recursive stack to record searched nodes. Used for detecting dependency cycles.
    final HashSet<String> recStack = new HashSet<>();
    // Nodes that have already been visited and added edges.
    final HashSet<String> visited = new HashSet<>();
    for (final AzkabanNode node : azkabanFlow.getNodes().values()) {
      addEdges(node, azkabanFlow, recStack, visited);
    }
  }

  private void addEdges(final AzkabanNode node, final AzkabanFlow azkabanFlow,
      final HashSet<String> recStack, final HashSet<String> visited) {
    if (!visited.contains(node.getName())) {
      recStack.add(node.getName());
      visited.add(node.getName());
      final List<String> dependsOnList = node.getDependsOn();
      for (final String parent : dependsOnList) {
        final Edge edge = new Edge(parent, node.getName());
        if (!this.edgeMap.containsKey(azkabanFlow.getName())) {
          this.edgeMap.put(azkabanFlow.getName(), new ArrayList<>());
        }
        this.edgeMap.get(azkabanFlow.getName()).add(edge);

        if (recStack.contains(parent)) {
          // Cycles found, including self cycle.
          edge.setError("Cycles found.");
          this.errors.add("Cycles found at " + edge.getId());
        } else if (!azkabanFlow.getNodes().containsKey(parent)) {
          // Dependency not found.
          edge.setError("Dependency not found.");
          this.errors.add(node.getName() + " cannot find dependency "
              + parent);
        } else {
          // Valid edge. Continue to process the parent node recursively.
          addEdges(azkabanFlow.getNode(parent), azkabanFlow, recStack, visited);
        }
      }
      recStack.remove(node.getName());
    }
  }

  public void checkJobProperties(final Project project) {
    // Todo jamiesjc: implement the check later
  }

}
