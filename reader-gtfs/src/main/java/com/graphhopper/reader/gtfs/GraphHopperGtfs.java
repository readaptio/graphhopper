/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.reader.gtfs;

import com.google.transit.realtime.GtfsRealtime;
import com.graphhopper.*;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.routing.QueryGraph;
import com.graphhopper.routing.VirtualEdgeIteratorState;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.*;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.*;
import com.graphhopper.util.exceptions.PointNotFoundException;
import com.graphhopper.util.shapes.GHPoint;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static com.graphhopper.util.Parameters.PT.PROFILE_QUERY;

public final class GraphHopperGtfs implements GraphHopperAPI {

    public static class Factory {
        private final TranslationMap translationMap;
        private final PtFlagEncoder flagEncoder;
        private final GraphHopperStorage graphHopperStorage;
        private final LocationIndex locationIndex;
        private final GtfsStorage gtfsStorage;

        private Factory(PtFlagEncoder flagEncoder, TranslationMap translationMap, GraphHopperStorage graphHopperStorage, LocationIndex locationIndex, GtfsStorage gtfsStorage) {
            this.flagEncoder = flagEncoder;
            this.translationMap = translationMap;
            this.graphHopperStorage = graphHopperStorage;
            this.locationIndex = locationIndex;
            this.gtfsStorage = gtfsStorage;
        }

        public GraphHopperGtfs createWith(GtfsRealtime.FeedMessage realtimeFeed) {
            return new GraphHopperGtfs(flagEncoder, translationMap, graphHopperStorage, locationIndex, gtfsStorage, RealtimeFeed.fromProtobuf(gtfsStorage, realtimeFeed));
        }

        public GraphHopperGtfs createWithoutRealtimeFeed() {
            return new GraphHopperGtfs(flagEncoder, translationMap, graphHopperStorage, locationIndex, gtfsStorage, RealtimeFeed.empty());
        }
    }

    public static Factory createFactory(PtFlagEncoder flagEncoder, TranslationMap translationMap, GraphHopperStorage graphHopperStorage, LocationIndex locationIndex, GtfsStorage gtfsStorage) {
        return new Factory(flagEncoder, translationMap, graphHopperStorage, locationIndex, gtfsStorage);
    }

    private final TranslationMap translationMap;
    private final PtFlagEncoder flagEncoder;
    private final GraphHopperStorage graphHopperStorage;
    private final LocationIndex locationIndex;
    private final GtfsStorage gtfsStorage;
    private final RealtimeFeed realtimeFeed;
    private final TripFromLabel tripFromLabel;

    private class RequestHandler {
        private final int maxVisitedNodesForRequest;
        private final int limitSolutions;
        private final Instant initialTime;
        private final boolean profileQuery;
        private final boolean arriveBy;
        private final boolean ignoreTransfers;
        private final double walkSpeedKmH;
        private final double maxWalkDistancePerLeg;
        private final double maxTransferDistancePerLeg;
        private final PtTravelTimeWeighting weighting;
        private final GHLocation enter;
        private final GHLocation exit;
        private final Translation translation;
        private final List<VirtualEdgeIteratorState> extraEdges = new ArrayList<>();
        private final PointList extraNodes = new PointList();
        private final Map<Integer, PathWrapper> walkPaths = new HashMap<>();

        private final GHResponse response = new GHResponse();
        private final QueryGraph queryGraph = new QueryGraph(graphHopperStorage);
        private GraphExplorer graphExplorer;

        RequestHandler(GHRequest request) {
            maxVisitedNodesForRequest = 1_000_000;
            profileQuery = request.getHints().getBool(PROFILE_QUERY, false);
            ignoreTransfers = request.getHints().getBool(Parameters.PT.IGNORE_TRANSFERS, false);
            limitSolutions = request.getHints().getInt(Parameters.PT.LIMIT_SOLUTIONS, profileQuery ? 5 : ignoreTransfers ? 1 : Integer.MAX_VALUE);
            final String departureTimeString = request.getHints().get(Parameters.PT.EARLIEST_DEPARTURE_TIME, "");
            try {
                initialTime = Instant.parse(departureTimeString);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(String.format("Illegal value for required parameter %s: [%s]", Parameters.PT.EARLIEST_DEPARTURE_TIME, departureTimeString));
            }
            arriveBy = request.getHints().getBool(Parameters.PT.ARRIVE_BY, false);
            walkSpeedKmH = request.getHints().getDouble(Parameters.PT.WALK_SPEED, 5.0);
            maxWalkDistancePerLeg = request.getHints().getDouble(Parameters.PT.MAX_WALK_DISTANCE_PER_LEG, Double.MAX_VALUE);
            maxTransferDistancePerLeg = request.getHints().getDouble(Parameters.PT.MAX_TRANSFER_DISTANCE_PER_LEG, Double.MAX_VALUE);
            weighting = createPtTravelTimeWeighting(flagEncoder, arriveBy, walkSpeedKmH);
            translation = translationMap.getWithFallBack(request.getLocale());
            if (request.getPoints().size() != 2) {
                throw new IllegalArgumentException("Exactly 2 points have to be specified, but was:" + request.getPoints().size());
            }
            enter = request.getPoints().get(0);
            exit = request.getPoints().get(1);
        }

        GHResponse route() {
            return route(new Consumer<Label>() {
                @Override
                public void accept(Label label) {

                }
            });
        }

        GHResponse route(Consumer<? super Label> action) {
            StopWatch stopWatch = new StopWatch().start();

            ArrayList<QueryResult> pointQueryResults = new ArrayList<>();
            ArrayList<QueryResult> allQueryResults = new ArrayList<>();
            PointList points = new PointList(2, false);
            if (enter instanceof GHPointLocation) {
                final QueryResult closest = findClosest(((GHPointLocation) enter).ghPoint, 0);
                pointQueryResults.add(closest);
                allQueryResults.add(closest);
                points.add(closest.getSnappedPoint());
            } else if (enter instanceof GHStationLocation) {
                final String stop_id = ((GHStationLocation) enter).stop_id;
                final int node = gtfsStorage.getStationNodes().get(stop_id);
                final QueryResult station = new QueryResult(graphHopperStorage.getNodeAccess().getLat(node), graphHopperStorage.getNodeAccess().getLon(node));
                station.setClosestNode(node);
                allQueryResults.add(station);
                points.add(graphHopperStorage.getNodeAccess().getLat(node), graphHopperStorage.getNodeAccess().getLon(node));
            }
            if (exit instanceof GHPointLocation) {
                final QueryResult closest = findClosest(((GHPointLocation) exit).ghPoint, 1);
                pointQueryResults.add(closest);
                allQueryResults.add(closest);
                points.add(closest.getSnappedPoint());
            } else if (exit instanceof GHStationLocation) {
                final String stop_id = ((GHStationLocation) exit).stop_id;
                final int node = gtfsStorage.getStationNodes().get(stop_id);
                final QueryResult station = new QueryResult(graphHopperStorage.getNodeAccess().getLat(node), graphHopperStorage.getNodeAccess().getLon(node));
                station.setClosestNode(node);
                allQueryResults.add(station);
                points.add(graphHopperStorage.getNodeAccess().getLat(node), graphHopperStorage.getNodeAccess().getLon(node));
            }

            queryGraph.lookup(pointQueryResults); // modifies queryGraph and queryResults!

            response.addDebugInfo("idLookup:" + stopWatch.stop().getSeconds() + "s");

            substitutePointWithVirtualNode(0, false, ((GHPointLocation) enter).ghPoint, allQueryResults);
            substitutePointWithVirtualNode(1, true, ((GHPointLocation) exit).ghPoint, allQueryResults);


            int startNode;
            int destNode;
            if (arriveBy) {
                startNode = allQueryResults.get(1).getClosestNode();
                destNode = allQueryResults.get(0).getClosestNode();
            } else {
                startNode = allQueryResults.get(0).getClosestNode();
                destNode = allQueryResults.get(1).getClosestNode();
            }
            List<Label> solutions = findPaths(startNode, destNode, action);
            parseSolutionsAndAddToResponse(solutions, points);
            return response;
        }

        private void substitutePointWithVirtualNode(int index, boolean reverse, GHPoint ghPoint, ArrayList<QueryResult> allQueryResults) {
            final GraphExplorer graphExplorer = new GraphExplorer(queryGraph, weighting, flagEncoder, gtfsStorage, realtimeFeed, reverse, new PointList(), Collections.emptyList(), true);

            extraNodes.add(ghPoint);

            int newNode = graphHopperStorage.getNodes() + 1000 + index;
            final List<Label> stationNodes = findStationNodes(graphExplorer, allQueryResults.get(index).getClosestNode(), reverse);
            for (Label stationNode : stationNodes) {
                final PathWrapper pathWrapper = tripFromLabel.parseSolutionIntoPath(reverse, flagEncoder, translation, graphExplorer, weighting, stationNode, new PointList());
                final VirtualEdgeIteratorState ulrich = new VirtualEdgeIteratorState(stationNode.edge,
                        -1, reverse ? stationNode.adjNode : newNode, reverse ? newNode : stationNode.adjNode, pathWrapper.getDistance(), 0, "ulrich", pathWrapper.getPoints());
                ulrich.setFlags(((PtFlagEncoder) weighting.getFlagEncoder()).setEdgeType(ulrich.getFlags(), reverse ? GtfsStorage.EdgeType.EXIT_PT : GtfsStorage.EdgeType.ENTER_PT));
                final long time = pathWrapper.getTime() / 1000;
                ulrich.setFlags(((PtFlagEncoder) weighting.getFlagEncoder()).setTime(ulrich.getFlags(), time));
                ulrich.setReverseEdge(ulrich);
                ulrich.setDistance(pathWrapper.getDistance());
                extraEdges.add(ulrich);
                walkPaths.put(newNode, pathWrapper);
            }

            final QueryResult virtualNode = new QueryResult(ghPoint.getLat(), ghPoint.getLon());
            virtualNode.setClosestNode(newNode);
            allQueryResults.set(index, virtualNode);
        }

        private List<Label> findStationNodes(GraphExplorer graphExplorer, int node, boolean reverse) {
            GtfsStorage.EdgeType edgeType = reverse ? GtfsStorage.EdgeType.EXIT_PT : GtfsStorage.EdgeType.ENTER_PT;
            MultiCriteriaLabelSetting router = new MultiCriteriaLabelSetting(graphExplorer, weighting, reverse, maxWalkDistancePerLeg, maxTransferDistancePerLeg, false, false, maxVisitedNodesForRequest);
            final Stream<Label> labels = router.calcLabels(node, -1, initialTime);
            return labels
                    .filter(current -> current.edge != -1 && flagEncoder.getEdgeType(graphExplorer.getEdgeIteratorState(current.edge, current.adjNode).getFlags()) == edgeType)
//                    .limit(limitSolutions)
                    .collect(Collectors.toList());
        }

        private QueryResult findClosest(GHPoint point, int indexForErrorMessage) {
            QueryResult source = locationIndex.findClosest(point.lat, point.lon, new EverythingButPt(flagEncoder));
            if (!source.isValid()) {
                throw new PointNotFoundException("Cannot find point: " + point, indexForErrorMessage);
            }
            return source;
        }

        private void parseSolutionsAndAddToResponse(List<Label> solutions, PointList waypoints) {
            for (Label solution : solutions) {
                final List<Trip.Leg> legs = tripFromLabel.getTrip(arriveBy, flagEncoder, translation, graphExplorer, weighting, solution);
                final PathWrapper pathWrapper = tripFromLabel.createPathWrapper(translation, waypoints, legs);
                response.add(pathWrapper);
            }
            response.getAll().sort(Comparator.comparingDouble(PathWrapper::getTime));
        }

        private List<Label> findPaths(int startNode, int destNode, Consumer<? super Label> action) {
            StopWatch stopWatch = new StopWatch().start();
            graphExplorer = new GraphExplorer(queryGraph, weighting, flagEncoder, gtfsStorage, realtimeFeed, arriveBy, extraNodes, extraEdges, false);
            MultiCriteriaLabelSetting router = new MultiCriteriaLabelSetting(graphExplorer, weighting, arriveBy, maxWalkDistancePerLeg, maxTransferDistancePerLeg, !ignoreTransfers, profileQuery, maxVisitedNodesForRequest);
            final Stream<Label> labels = router.calcLabels(startNode, destNode, initialTime);
            List<Label> solutions = labels
                    .peek(action)
                    .filter(current -> destNode == current.adjNode)
//                    .filter(me -> me.nWalkDistanceConstraintViolations <= 0)
                    .limit(limitSolutions)
                    .collect(Collectors.toList());
            response.addDebugInfo("routing:" + stopWatch.stop().getSeconds() + "s");
            response.getHints().put("visited_nodes.sum", router.getVisitedNodes());
            response.getHints().put("visited_nodes.average", router.getVisitedNodes());
            return solutions;
        }
    }

    public GraphHopperGtfs(PtFlagEncoder flagEncoder, TranslationMap translationMap, GraphHopperStorage graphHopperStorage, LocationIndex locationIndex, GtfsStorage gtfsStorage, RealtimeFeed realtimeFeed) {
        this.flagEncoder = flagEncoder;
        this.translationMap = translationMap;
        this.graphHopperStorage = graphHopperStorage;
        this.locationIndex = locationIndex;
        this.gtfsStorage = gtfsStorage;
        this.realtimeFeed = realtimeFeed;
        this.tripFromLabel = new TripFromLabel(this.gtfsStorage);
    }

    public static GtfsStorage createGtfsStorage() {
        return new GtfsStorage();
    }

    public static GHDirectory createGHDirectory(String graphHopperFolder) {
        return new GHDirectory(graphHopperFolder, DAType.RAM_STORE);
    }

    public static TranslationMap createTranslationMap() {
        return new TranslationMap().doImport();
    }

    public static GraphHopperStorage createOrLoad(GHDirectory directory, EncodingManager encodingManager, PtFlagEncoder ptFlagEncoder, GtfsStorage gtfsStorage, boolean createWalkNetwork, Collection<String> gtfsFiles, Collection<String> osmFiles) {
        GraphHopperStorage graphHopperStorage = new GraphHopperStorage(directory, encodingManager, false, gtfsStorage);
        if (graphHopperStorage.loadExisting()) {
            return graphHopperStorage;
        } else {
            graphHopperStorage.create(1000);
            for (String osmFile : osmFiles) {
                OSMReader osmReader = new OSMReader(graphHopperStorage);
                osmReader.setFile(new File(osmFile));
                osmReader.setDontCreateStorage(true);
                try {
                    osmReader.readGraph();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            int id = 0;
            for (String gtfsFile : gtfsFiles) {
                try {
                    ((GtfsStorage) graphHopperStorage.getExtension()).loadGtfsFromFile("gtfs_" + id++, new ZipFile(gtfsFile));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (createWalkNetwork) {
                FakeWalkNetworkBuilder.buildWalkNetwork(((GtfsStorage) graphHopperStorage.getExtension()).getGtfsFeeds().values(), graphHopperStorage, ptFlagEncoder, Helper.DIST_EARTH);
            }
            LocationIndex walkNetworkIndex;
            if (graphHopperStorage.getNodes() > 0) {
                walkNetworkIndex = new LocationIndexTree(graphHopperStorage, new RAMDirectory()).prepareIndex();
            } else {
                walkNetworkIndex = new EmptyLocationIndex();
            }
            for (int i = 0; i < id; i++) {
                new GtfsReader("gtfs_" + i, graphHopperStorage, walkNetworkIndex).readGraph();
            }
            graphHopperStorage.flush();
            return graphHopperStorage;
        }
    }


    public static LocationIndex createOrLoadIndex(GHDirectory directory, GraphHopperStorage graphHopperStorage, PtFlagEncoder flagEncoder) {
        final EverythingButPt everythingButPt = new EverythingButPt(flagEncoder);
        Graph walkNetwork = GraphSupport.filteredView(graphHopperStorage, everythingButPt);
        LocationIndex locationIndex = new LocationIndexTree(walkNetwork, directory);
        if (!locationIndex.loadExisting()) {
            locationIndex.prepareIndex();
        }
        return locationIndex;
    }

    public boolean load(String graphHopperFolder) {
        throw new IllegalStateException("We are always loaded, or we wouldn't exist.");
    }

    @Override
    public GHResponse route(GHRequest request) {
        return new RequestHandler(request).route();
    }

    public GHResponse routeStreaming(GHRequest request, Consumer<? super Label> action) {
        return new RequestHandler(request).route(action);
    }

    private static PtTravelTimeWeighting createPtTravelTimeWeighting(PtFlagEncoder encoder, boolean arriveBy, double walkSpeedKmH) {
        PtTravelTimeWeighting weighting = new PtTravelTimeWeighting(encoder, walkSpeedKmH);
        if (arriveBy) {
            weighting = weighting.reverse();
        }
        return weighting;
    }

}
