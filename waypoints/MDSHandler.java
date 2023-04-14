import io.nuclio.Context;
import io.nuclio.Event;
import io.nuclio.EventHandler;
import io.nuclio.Response;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

public class MDSHandler implements EventHandler {

    private static DateTimeFormatter sdfISO8601 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
	private static final String connectionUri = System.getenv("MONGO_URI");
    private static final String TOKEN_VALUE = "Basic " + System.getenv("TOKEN");

    private static MongoTemplate template;
	static {
		ConnectionString connectionString = new ConnectionString(connectionUri);
        MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
            .applyConnectionString(connectionString)
            .build();
        
        MongoClient mongo = MongoClients.create(mongoClientSettings);		
		template = new MongoTemplate(mongo, "playngo-engine");
	}

    @Override
    public Response handleEvent(Context context, Event event) {
        String token = event.getHeaderString("Authorization");
        if (!TOKEN_VALUE.equals(token)) {
            return new Response().setStatusCode(401).setBody("");
        }

        String end_time = event.getFieldString("end_time");
        List<MDSTrip> list = getTracks(end_time, template);
        context.getLogger().info("list = " + list.size());
        try {
            return new Response()
            .setBody(new ObjectMapper().writeValueAsString(list))
            .setContentType("application/json");
        } catch(Exception e) {
            context.getLogger().error("error = " + e.getMessage());
            return new Response().setBody("[]").setContentType("application/json");
        }
    }

    public List<MDSTrip> getTracks(String end_time, MongoTemplate template) {

		LocalDateTime localDate = LocalDateTime.parse(end_time, sdfISO8601);
		LocalDateTime localDateS = localDate.withMinute(0).withSecond(0);
		LocalDateTime localDateE = localDate.withMinute(59).withSecond(59);
		Criteria criteria = new Criteria("territoryId").is("Ferrara")
				.and("freeTrackingTransport").ne(null)
				.and("startTime").gte(localDateS).lte(localDateE);
		Query query = new Query(criteria);
		List<TrackedInstance> tracks = template.find(query, TrackedInstance.class, "trackedInstances");
		
		return tracks.stream()
				.filter(ti -> ti.getGeolocationEvents() != null && ti.getGeolocationEvents().size() > 0 && ti.getValidationResult() != null && ti.getValidationResult().getValidationStatus() != null)
				.map(ti -> new MDSTrip(ti)).collect(Collectors.toList());
	}
	
	/**
	 * Supporting classes
	 */
	
	public static enum TravelValidity {
		VALID, INVALID, PENDING
	}

	public static class MDSTrip {

		private String vehicle_type, trip_id;
		private Long trip_duration, trip_distance, accuracy;
		private Long start_time, end_time;
		private Route route;

		public MDSTrip() {
			super();
		}

		public MDSTrip(TrackedInstance instance) {
			vehicle_type = toMDSType(instance);
			trip_id = instance.getId();
			trip_duration = instance.getValidationResult().getValidationStatus().getDuration();
			trip_distance = (long) Math.floor(instance.getValidationResult().getDistance());
			route = new Route();
			route.features = new LinkedList<>();
			accuracy = 0l;
			start_time = Long.MAX_VALUE;
			end_time = 0l;
			int idx = 0;
			for (Geolocation g : instance.getGeolocationEvents()) {
				accuracy = (idx * accuracy + g.getAccuracy()) / (idx + 1);
				if (g.getRecorded_at().getTime() < start_time)
					start_time = g.getRecorded_at().getTime();
				if (g.getRecorded_at().getTime() > end_time)
					end_time = g.getRecorded_at().getTime();
				Feature f = new Feature();
				f.geometry = new Geometry();
				f.geometry.coordinates = new double[] { g.getLongitude(), g.getLatitude() };
				f.properties.put("timestamp", g.getRecorded_at());
				f.properties.put("speed", g.getSpeed());
				f.properties.put("accuracy", g.getAccuracy());
				f.properties.put("activity_type", g.getActivity_type());
				f.properties.put("activity_confidence", g.getActivity_confidence());
				route.features.add(f);
			}
		}

		protected String toMDSType(TrackedInstance instance) {
			return instance.getFreeTrackingTransport().equals("bike") ? "bicycle" : instance.getFreeTrackingTransport();
		}

		public String getVehicle_type() {
			return vehicle_type;
		}

		public void setVehicle_type(String vehicle_type) {
			this.vehicle_type = vehicle_type;
		}

		public String getTrip_id() {
			return trip_id;
		}

		public void setTrip_id(String trip_id) {
			this.trip_id = trip_id;
		}

		public Long getTrip_duration() {
			return trip_duration;
		}

		public void setTrip_duration(Long trip_duration) {
			this.trip_duration = trip_duration;
		}

		public Long getTrip_distance() {
			return trip_distance;
		}

		public void setTrip_distance(Long trip_distance) {
			this.trip_distance = trip_distance;
		}

		public Long getAccuracy() {
			return accuracy;
		}

		public void setAccuracy(Long accuracy) {
			this.accuracy = accuracy;
		}

		public Long getStart_time() {
			return start_time;
		}

		public void setStart_time(Long start_time) {
			this.start_time = start_time;
		}

		public Long getEnd_time() {
			return end_time;
		}

		public void setEnd_time(Long end_time) {
			this.end_time = end_time;
		}

		public Route getRoute() {
			return route;
		}

		public void setRoute(Route route) {
			this.route = route;
		}

		public static class Route {
			private String type = "FeatureCollection";
			private List<Feature> features;

			public String getType() {
				return type;
			}

			public void setType(String type) {
				this.type = type;
			}

			public List<Feature> getFeatures() {
				return features;
			}

			public void setFeautures(List<Feature> features) {
				this.features = features;
			}

		}

		public static class Feature {
			private String type = "Feature";
			private Geometry geometry;
			private Map<String, Object> properties = new HashMap<>();

			public String getType() {
				return type;
			}

			public void setType(String type) {
				this.type = type;
			}

			public Geometry getGeometry() {
				return geometry;
			}

			public void setGeometry(Geometry geometry) {
				this.geometry = geometry;
			}

			public Map<String, Object> getProperties() {
				return properties;
			}

			public void setProperties(Map<String, Object> properties) {
				this.properties = properties;
			}
		}

		public static class Geometry {
			private String type = "Point";
			private double[] coordinates;

			public String getType() {
				return type;
			}

			public void setType(String type) {
				this.type = type;
			}

			public double[] getCoordinates() {
				return coordinates;
			}

			public void setCoordinates(double[] coordinates) {
				this.coordinates = coordinates;
			}

		}
	}

	public static class TrackedInstance {

		private String id;

		private String clientId;

		private String userId;
		private String nickname;

		private String territoryId;

		private String multimodalId;
		private String sharedTravelId;

		private String freeTrackingTransport;

		private Collection<Geolocation> geolocationEvents;
		private Boolean started = Boolean.FALSE;
		private Boolean complete = Boolean.FALSE;
		private Boolean validating = Boolean.FALSE;

		private String deviceInfo;

		private Date startTime;

		private ValidationResult validationResult;

		private TravelValidity changedValidity;
		private String note;
		private Boolean approved;
		private Boolean toCheck;

//	private int groupId;

		private Map<String, Double> overriddenDistances;

		private Boolean suspect;

		public TrackedInstance() {
			geolocationEvents = new HashSet<>();
			validationResult = new ValidationResult();
		}

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String travelId) {
			this.clientId = travelId;
		}

		public String getUserId() {
			return userId;
		}

		public void setUserId(String userId) {
			this.userId = userId;
		}

		public String getTerritoryId() {
			return territoryId;
		}

		public void setTerritoryId(String territoryId) {
			this.territoryId = territoryId;
		}

		public String getMultimodalId() {
			return multimodalId;
		}

		public void setMultimodalId(String multimodalId) {
			this.multimodalId = multimodalId;
		}

		public Collection<Geolocation> getGeolocationEvents() {
			return geolocationEvents;
		}

		public void setGeolocationEvents(Collection<Geolocation> geolocationEvents) {
			this.geolocationEvents = geolocationEvents;
		}

		public Boolean getStarted() {
			return started;
		}

		public void setStarted(Boolean started) {
			this.started = started;
		}

		public Boolean getComplete() {
			return complete;
		}

		public void setComplete(Boolean complete) {
			this.complete = complete;
		}

		public ValidationResult getValidationResult() {
			return validationResult;
		}

		public void setValidationResult(ValidationResult validationResult) {
			this.validationResult = validationResult;
		}

		/**
		 * @return the deviceInfo
		 */
		public String getDeviceInfo() {
			return deviceInfo;
		}

		/**
		 * @param deviceInfo the deviceInfo to set
		 */
		public void setDeviceInfo(String deviceInfo) {
			this.deviceInfo = deviceInfo;
		}

		/**
		 * @return the freeTrackingTransport
		 */
		public String getFreeTrackingTransport() {
			return freeTrackingTransport;
		}

		/**
		 * @param freeTrackingTransport the freeTrackingTransport to set
		 */
		public void setFreeTrackingTransport(String freeTrackingTransport) {
			this.freeTrackingTransport = freeTrackingTransport;
		}

		public TravelValidity getChangedValidity() {
			return changedValidity;
		}

		public void setChangedValidity(TravelValidity changedValidity) {
			this.changedValidity = changedValidity;
		}

		public Boolean getApproved() {
			return approved;
		}

		public void setApproved(Boolean approved) {
			this.approved = approved;
		}

		public Boolean getToCheck() {
			return toCheck;
		}

		public void setToCheck(Boolean toCheck) {
			this.toCheck = toCheck;
		}

//	public int getGroupId() {
//		return groupId;
//	}
//
//	public void setGroupId(int groupId) {
//		this.groupId = groupId;
//	}

		public Map<String, Double> getOverriddenDistances() {
			return overriddenDistances;
		}

		public void setOverriddenDistances(Map<String, Double> overriddenDistances) {
			this.overriddenDistances = overriddenDistances;
		}

		public Boolean getSuspect() {
			return suspect;
		}

		public void setSuspect(Boolean suspect) {
			this.suspect = suspect;
		}

		@Override
		public String toString() {
			return id;
		}

		public String getSharedTravelId() {
			return sharedTravelId;
		}

		public void setSharedTravelId(String sharedTravelId) {
			this.sharedTravelId = sharedTravelId;
		}

		public Boolean getValidating() {
			return validating;
		}

		public void setValidating(Boolean validating) {
			this.validating = validating;
		}

		public String getNote() {
			return note;
		}

		public void setNote(String note) {
			this.note = note;
		}

		public Date getStartTime() {
			return startTime;
		}

		public void setStartTime(Date startTime) {
			this.startTime = startTime;
		}

		public String getNickname() {
			return nickname;
		}

		public void setNickname(String nickname) {
			this.nickname = nickname;
		}

	}

	public static class Geolocation implements Comparable<Geolocation> {

		private String userId;
		private String travelId;
		private String multimodalId;
		private String sharedTravelId;

		private String uuid;
		private String device_id;
		private String device_model;
		private Double latitude;
		private Double longitude;

		private Long accuracy;

		private Double altitude;
		private Double speed;
		private Double heading;

		private String activity_type;
		private Long activity_confidence;
		private Double battery_level;

		private Boolean battery_is_charging;
		private Boolean is_moving;

		private Object geofence;

		private Date recorded_at;
		private Date created_at;

		private String certificate;

		private double[] geocoding;

		public Geolocation() {
		}

		public Geolocation(Double latitude, Double longitude, Date recorded_at) {
			super();
			this.latitude = latitude;
			this.longitude = longitude;
			this.recorded_at = recorded_at;
			this.geocoding = new double[] { longitude, latitude };
		}

		public Geolocation(Double latitude, Double longitude, Date recorded_at, Long accuracy) {
			super();
			this.latitude = latitude;
			this.longitude = longitude;
			this.recorded_at = recorded_at;
			this.accuracy = accuracy;
			this.geocoding = new double[] { longitude, latitude };
		}

		public String getUserId() {
			return userId;
		}

		public void setUserId(String userId) {
			this.userId = userId;
		}

		public String getTravelId() {
			return travelId;
		}

		public void setTravelId(String travelId) {
			this.travelId = travelId;
		}

		public String getMultimodalId() {
			return multimodalId;
		}

		public void setMultimodalId(String multimodalId) {
			this.multimodalId = multimodalId;
		}

		public String getUuid() {
			return uuid;
		}

		public void setUuid(String uuid) {
			this.uuid = uuid;
		}

		public String getDevice_id() {
			return device_id;
		}

		public void setDevice_id(String device_id) {
			this.device_id = device_id;
		}

		public String getDevice_model() {
			return device_model;
		}

		public void setDevice_model(String device_model) {
			this.device_model = device_model;
		}

		public Double getLatitude() {
			return latitude;
		}

		public void setLatitude(Double latitude) {
			this.latitude = latitude;
		}

		public Double getLongitude() {
			return longitude;
		}

		public void setLongitude(Double longitude) {
			this.longitude = longitude;
		}

		public Long getAccuracy() {
			return accuracy;
		}

		public void setAccuracy(Long accuracy) {
			this.accuracy = accuracy;
		}

		public Double getAltitude() {
			return altitude;
		}

		public void setAltitude(Double altitude) {
			this.altitude = altitude;
		}

		public Double getSpeed() {
			return speed;
		}

		public void setSpeed(Double speed) {
			this.speed = speed;
		}

		public Double getHeading() {
			return heading;
		}

		public void setHeading(Double heading) {
			this.heading = heading;
		}

		public String getActivity_type() {
			return activity_type;
		}

		public void setActivity_type(String activity_type) {
			this.activity_type = activity_type;
		}

		public Long getActivity_confidence() {
			return activity_confidence;
		}

		public void setActivity_confidence(Long activity_confidence) {
			this.activity_confidence = activity_confidence;
		}

		public Double getBattery_level() {
			return battery_level;
		}

		public void setBattery_level(Double battery_level) {
			this.battery_level = battery_level;
		}

		public Boolean getBattery_is_charging() {
			return battery_is_charging;
		}

		public void setBattery_is_charging(Boolean battery_is_charging) {
			this.battery_is_charging = battery_is_charging;
		}

		public Boolean getIs_moving() {
			return is_moving;
		}

		public void setIs_moving(Boolean is_moving) {
			this.is_moving = is_moving;
		}

		public Object getGeofence() {
			return geofence;
		}

		public void setGeofence(Object geofence) {
			this.geofence = geofence;
		}

		public Date getRecorded_at() {
			return recorded_at;
		}

		public void setRecorded_at(Date recorded_at) {
			this.recorded_at = recorded_at;
		}

		public Date getCreated_at() {
			return created_at;
		}

		public void setCreated_at(Date created_at) {
			this.created_at = created_at;
		}

		public double[] getGeocoding() {
			return geocoding;
		}

		public void setGeocoding(double[] geocoding) {
			this.geocoding = geocoding;
		}

		public String getCertificate() {
			return certificate;
		}

		public void setCertificate(String certificate) {
			this.certificate = certificate;
		}

		public String getSharedTravelId() {
			return sharedTravelId;
		}

		public void setSharedTravelId(String sharedTravelId) {
			this.sharedTravelId = sharedTravelId;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((recorded_at == null) ? 0 : recorded_at.hashCode());
			result = prime * result + ((uuid == null) ? 0 : uuid.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Geolocation other = (Geolocation) obj;
			if (recorded_at == null) {
				if (other.recorded_at != null)
					return false;
			} else if (!recorded_at.equals(other.recorded_at))
				return false;
			if (uuid == null) {
				if (other.uuid != null)
					return false;
			} else if (!uuid.equals(other.uuid))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return latitude + "," + longitude + "@" + recorded_at;
		}

		@Override
		public int compareTo(Geolocation o) {
			return recorded_at.compareTo(o.recorded_at);
		}

	}

	public static class ValidationResult {


//	private int geoLocationsN;
//	private int legsLocationsN;
//	private int matchedLocationsN;
//	private Boolean matchedLocations;
//	private Boolean tooFewPoints;
//	private Boolean inAreas;
//	
//	private Set<String> geoActivities;
//	private Set<String> legsActivities;
//	private Boolean matchedActivities;
//	private Boolean tooFast;
//	
//	private TravelValidity travelValidity = TravelValidity.PENDING;
//	
//	private Double averageSpeed;
//	private Double maxSpeed;
//	
//	private Double distance;
//	private Long time;
//	
//	private Double validatedDistance;
//	private Long validatedTime;	

		private Boolean plannedAsFreeTracking;
		private ValidationStatus validationStatus;

		public ValidationStatus getValidationStatus() {
			if (validationStatus == null) {
				validationStatus = new ValidationStatus();
				validationStatus.setValidationOutcome(TravelValidity.PENDING);
			}
			return validationStatus;
		}

		public void setValidationStatus(ValidationStatus validationStatus) {
			this.validationStatus = validationStatus;
		}

//	public void reset() {
//		matchedLocations = true;
//		matchedActivities = true;
//		tooFast = false;
//		travelValidity =  TravelValidity.PENDING;
//	}
//	
//	public int getGeoLocationsN() {
//		return geoLocationsN;
//	}
//
//	public void setGeoLocationsN(int geoLocationsN) {
//		this.geoLocationsN = geoLocationsN;
//	}
//
//	public int getLegsLocationsN() {
//		return legsLocationsN;
//	}
//
//	public void setLegsLocationsN(int legsLocationsN) {
//		this.legsLocationsN = legsLocationsN;
//	}
//
//	public int getMatchedLocationsN() {
//		return matchedLocationsN;
//	}
//
//	public void setMatchedLocationsN(int matchedLocationsN) {
//		this.matchedLocationsN = matchedLocationsN;
//	}
//
//	public Boolean getMatchedLocations() {
//		return matchedLocations;
//	}
//
//	public void setMatchedLocations(Boolean matchedLocations) {
//		this.matchedLocations = matchedLocations;
//	}
//
//	public Boolean getTooFewPoints() {
//		return tooFewPoints;
//	}
//
//	public void setTooFewPoints(Boolean tooFewPoints) {
//		this.tooFewPoints = tooFewPoints;
//	}
//
//	public Boolean getTooFast() {
//		return tooFast;
//	}
//
//	public Boolean getInAreas() {
//		return inAreas;
//	}
//
//	public void setInAreas(Boolean inAreas) {
//		this.inAreas = inAreas;
//	}
//
//	public void setTooFast(Boolean walkOnly) {
//		this.tooFast = walkOnly;
//	}
//
//	public Set<String> getGeoActivities() {
//		return geoActivities;
//	}
//
//	public void setGeoActivities(Set<String> geoActivities) {
//		this.geoActivities = geoActivities;
//	}
//
//	public Set<String> getLegsActivities() {
//		return legsActivities;
//	}
//
//	public void setLegsActivities(Set<String> legsActivities) {
//		this.legsActivities = legsActivities;
//	}
//
//	public Boolean getMatchedActivities() {
//		return matchedActivities;
//	}
//
//	public void setMatchedActivities(Boolean matchedActivities) {
//		this.matchedActivities = matchedActivities;
//	}
//
//	public Double getAverageSpeed() {
//		return averageSpeed;
//	}
//
//	public void setAverageSpeed(Double averageSpeed) {
//		this.averageSpeed = averageSpeed;
//	}
//
//	public Double getMaxSpeed() {
//		return maxSpeed;
//	}
//
//	public void setMaxSpeed(Double maxSpeed) {
//		this.maxSpeed = maxSpeed;
//	}
//
		public Double getDistance() {
			return getValidationStatus().getDistance();
		}

//
//	public void setDistance(Double distance) {
//		this.distance = distance;
//	}
//
		public Long getTime() {
			return getValidationStatus().getDuration();
		}

//
//	public void setTime(Long time) {
//		this.time = time;
//	}
//
		public TravelValidity getTravelValidity() {
			return getValidationStatus().getValidationOutcome();
		}
//
//	public void setTravelValidity(TravelValidity travelValidity) {
//		this.travelValidity = travelValidity;
//	}
//
//	public Double getValidatedDistance() {
//		return validatedDistance;
//	}
//
//	public void setValidatedDistance(Double validatedDistance) {
//		this.validatedDistance = validatedDistance;
//	}
//
//	public Long getValidatedTime() {
//		return validatedTime;
//	}
//
//	public void setValidatedTime(Long validatedTime) {
//		this.validatedTime = validatedTime;
//	}

		public Boolean getPlannedAsFreeTracking() {
			return plannedAsFreeTracking;
		}

		public void setPlannedAsFreeTracking(Boolean plannedAsFreeTracking) {
			this.plannedAsFreeTracking = plannedAsFreeTracking;
		}

		boolean valid;

		public boolean isValid() {
			return valid;
		}

		public void setValid(boolean valid) {
			this.valid = valid;
		}

	}
	
	public static class ValidationStatus {

		public  static final int ACCURACY_THRESHOLD = 100;

		private static final SimpleDateFormat DT_FORMATTER = new SimpleDateFormat("HH:mm:ss");

		public enum TRIP_TYPE {FREE, PLANNED, SHARED};
		public enum MODE_TYPE {walk, bike, bus, train, multi, other, car, boat};
		
		public enum ERROR_TYPE {TOO_SHORT, TOO_SLOW, TOO_FAST, OUT_OF_AREA, DOES_NOT_MATCH, DATA_HOLE, NO_DATA, SHARED_DOES_NOT_MATCH};
		
		private TRIP_TYPE tripType;
		private MODE_TYPE modeType;
		
		private long duration; // seconds
		private double distance; // meters
		private int locations; // number of geopoints
		
		private double averageSpeed; // km/h
		private double maxSpeed; // km/h
		
		private double accuracyRank; // percentage of points with high precision (< 50m)
		
		// in case of planned trip, distances mapped on transport modes as of plan
		private Map<MODE_TYPE, Double> plannedDistances;
		// in case of planned trip, actual distances mapped on transport modes
		// in case of free trip, actual distances out of measurements
		private Map<MODE_TYPE, Double> effectiveDistances;
		
		// split data: thresholds, speed, intervals
		private double splitSpeedThreshold;
		private double splitStopTimeThreshold;
		private double splitMinFastDurationThreshold; 
		// validation data: validity match threshold
		private double validityThreshold;// percentage of coverage
		private double matchThreshold; // Hausdorff distance for track match
		private double coverageThreshold; // percentage of distance matched to consider valid
		
		private List<Interval> intervals;
		private int matchedIntervals;

		private TravelValidity validationOutcome;
		
		private ERROR_TYPE error;
		
		private boolean certified;
		
		private String polyline;
		
		private boolean toCheck;
		

		public TRIP_TYPE getTripType() {
			return tripType;
		}


		public void setTripType(TRIP_TYPE tripType) {
			this.tripType = tripType;
		}


		public MODE_TYPE getModeType() {
			return modeType;
		}


		public void setModeType(MODE_TYPE modeType) {
			this.modeType = modeType;
		}


		public long getDuration() {
			return duration;
		}


		public void setDuration(long duration) {
			this.duration = duration;
		}


		public double getDistance() {
			return distance;
		}


		public void setDistance(double distance) {
			this.distance = distance;
		}


		public int getLocations() {
			return locations;
		}


		public void setLocations(int locations) {
			this.locations = locations;
		}


		public double getAverageSpeed() {
			return averageSpeed;
		}


		public void setAverageSpeed(double averageSpeed) {
			this.averageSpeed = averageSpeed;
		}


		public double getMaxSpeed() {
			return maxSpeed;
		}


		public void setMaxSpeed(double maxSpeed) {
			this.maxSpeed = maxSpeed;
		}


		public Map<MODE_TYPE, Double> getPlannedDistances() {
			if (plannedDistances == null) plannedDistances = new HashMap<>();
			return plannedDistances;
		}


		public void setPlannedDistances(Map<MODE_TYPE, Double> plannedDistances) {
			this.plannedDistances = plannedDistances;
		}


		public Map<MODE_TYPE, Double> getEffectiveDistances() {
			if (effectiveDistances == null) effectiveDistances = new HashMap<>();
			return effectiveDistances;
		}


		public void setEffectiveDistances(Map<MODE_TYPE, Double> effectiveDistances) {
			this.effectiveDistances = effectiveDistances;
		}


		public double getSplitSpeedThreshold() {
			return splitSpeedThreshold;
		}


		public void setSplitSpeedThreshold(double splitSpeedThreshold) {
			this.splitSpeedThreshold = splitSpeedThreshold;
		}


		public double getSplitStopTimeThreshold() {
			return splitStopTimeThreshold;
		}


		public void setSplitStopTimeThreshold(double splitStopTimeThreshold) {
			this.splitStopTimeThreshold = splitStopTimeThreshold;
		}


		public double getSplitMinFastDurationThreshold() {
			return splitMinFastDurationThreshold;
		}


		public void setSplitMinFastDurationThreshold(double splitMinFastDurationThreshold) {
			this.splitMinFastDurationThreshold = splitMinFastDurationThreshold;
		}


		public double getValidityThreshold() {
			return validityThreshold;
		}


		public void setValidityThreshold(double validityThreshold) {
			this.validityThreshold = validityThreshold;
		}

		public double getMatchThreshold() {
			return matchThreshold;
		}



		public void setMatchThreshold(double matchThreshold) {
			this.matchThreshold = matchThreshold;
		}


		public double getCoverageThreshold() {
			return coverageThreshold;
		}



		public void setCoverageThreshold(double coverageThreshold) {
			this.coverageThreshold = coverageThreshold;
		}



		public List<Interval> getIntervals() {
			return intervals;
		}


		public void setIntervals(List<Interval> intervals) {
			this.intervals = intervals;
		}

		public int getMatchedIntervals() {
			return matchedIntervals;
		}



		public void setMatchedIntervals(int matchedIntervals) {
			this.matchedIntervals = matchedIntervals;
		}



		public TravelValidity getValidationOutcome() {
			return validationOutcome;
		}


		public void setValidationOutcome(TravelValidity validationOutcome) {
//			if (TravelValidity.PENDING.equals(validationOutcome)) {
//				this.validationOutcome = TravelValidity.VALID;
//			} else {
//				this.validationOutcome = validationOutcome;
//			}
			this.validationOutcome = validationOutcome;
		}

		public String getPolyline() {
			return polyline;
		}
		public void setPolyline(String polyline) {
			this.polyline = polyline;
		}
		public ERROR_TYPE getError() {
			return error;
		}

		public void setError(ERROR_TYPE error) {
			this.error = error;
		}


		public double getAccuracyRank() {
			return accuracyRank;
		}

		public void setAccuracyRank(double accuracyRank) {
			this.accuracyRank = accuracyRank;
		}

		public boolean isCertified() {
			return certified;
		}
		public void setCertified(boolean certified) {
			this.certified = certified;
		}
		
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("ValidationStatus: \n");
			sb.append(String.format("      stats (duration / length / points / dist freq. / time freq. / accuracy): %d / %.0f / %d / %.4f / %.4f / %.2f\n", duration, (float)distance, locations, (float)(distance > 0 ? 1000.0 * locations / distance : 0), (float)(duration > 0 ? 60.0 * locations / duration : 0), (float) accuracyRank));
			sb.append(String.format("      speed (average / max): %.2f / %.2f\n", averageSpeed, maxSpeed));
			if (effectiveDistances != null){
				sb.append(			"      effective distances: "+effectiveDistances+"\n");
			}
			if (plannedDistances != null){
				sb.append(			"      planned distances: "+plannedDistances+"\n");
			}
			if (intervals != null){
				sb.append(String.format("      intervals: %d / %d\n", matchedIntervals, intervals.size()));
				for (Interval interval : intervals){
					sb.append(String.format("      		%s - %s: %.2f (%.2f%% of points, %.2f%% of distance)\n",DT_FORMATTER.format(new Date(interval.getStartTime())), DT_FORMATTER.format(new Date(interval.getEndTime())), interval.getMatch(), (float)(100.0*(interval.getEnd()-interval.getStart())/2/locations), interval.getDistance()/distance*100));
				}
			}
			sb.append(	          "      outcome: "); sb.append(validationOutcome);
			if (error != null) {
				sb.append(" (");sb.append(error); sb.append(")");
			}
			return sb.toString();
		}




		public static class Interval {
			private int start, end;
			private long startTime, endTime;
			private double distance;
			private double match; // percent of validity
			public int getStart() {
				return start;
			}
			public void setStart(int start) {
				this.start = start;
			}
			public int getEnd() {
				return end;
			}
			public void setEnd(int end) {
				this.end = end;
			}
			public long getStartTime() {
				return startTime;
			}
			public void setStartTime(long startTime) {
				this.startTime = startTime;
			}
			public long getEndTime() {
				return endTime;
			}
			public void setEndTime(long endTime) {
				this.endTime = endTime;
			}
			public double getDistance() {
				return distance;
			}
			public void setDistance(double distance) {
				this.distance = distance;
			}
			public double getMatch() {
				return match;
			}
			public void setMatch(double match) {
				this.match = match;
			}
			
		}




		public boolean isToCheck() {
			return toCheck;
		}
		public void setToCheck(boolean toCheck) {
			this.toCheck = toCheck;
		}

	}

}
