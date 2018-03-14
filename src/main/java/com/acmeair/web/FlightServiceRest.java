/*******************************************************************************
* Copyright (c) 2013 IBM Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*******************************************************************************/

package com.acmeair.web;

import com.acmeair.securityutils.SecurityUtils;
import com.acmeair.service.FlightService;

import java.io.StringReader;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonBuilderFactory;
import javax.json.JsonReader;
import javax.json.JsonReaderFactory;
import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.metrics.annotation.Timed;

@Path("/")
public class FlightServiceRest {
  
  @Inject
  private FlightService flightService;
    
  @Inject
  private SecurityUtils secUtils;
  
  private static final JsonReaderFactory jsonReaderFactory = Json.createReaderFactory(null);
  private static final JsonBuilderFactory jsonObjectFactory  = Json.createBuilderFactory(null);
  
  private static final int year =  Calendar.getInstance().get(Calendar.YEAR);
  /**
   * Get flights.
   */
  // Had to change Dates to strings + extra processing below because 
  // Payara did not handle the Dates well.
  
  @POST
  @Path("/queryflights")
  @Consumes({"application/x-www-form-urlencoded"})
  @Produces("text/plain")
  @Timed(name = "com.acmeair.web.FlightServiceRest.getTripFlights", tags = "app=flightservice-java")
  public String getTripFlights(
          @FormParam("fromAirport") String fromAirport,
          @FormParam("toAirport") String toAirport,
          @FormParam("fromDate") String fromDate,
          @FormParam("returnDate") String returnDate,
          @FormParam("oneWay") boolean oneWay
  ) throws ParseException {

    if (!flightService.isPopulated()) {
      throw new RuntimeException("Flight DB has not been populated");
    }
    
    Date fromDateAsDate = convertStringToDate(fromDate);
    Date returnDateAsDate = convertStringToDate(returnDate);
    
    return getFlightOptions(fromAirport,toAirport,fromDateAsDate,returnDateAsDate,oneWay);
  }

  /**
   * Get reward miles for flight segment.
   */
  @POST
  @Path("/getrewardmiles")
  @Consumes({"application/x-www-form-urlencoded"})
  @Produces("application/json")
  @Timed(name = "com.acmeair.web.FlightServiceRest.getRewardsMiles", 
      tags = "app=flightservice-java")
  public MilesResponse getRewardMiles(
          @HeaderParam("acmeair-id") String headerId,
          @HeaderParam("acmeair-date") String headerDate,
          @HeaderParam("acmeair-sig-body") String headerSigBody, 
          @HeaderParam("acmeair-signature") String headerSig,
          @FormParam("flightSegment") String segmentId
  ) {
    if (secUtils.secureServiceCalls()) { 
      String body = "flightSegment=" + segmentId;
      secUtils.verifyBodyHash(body, headerSigBody);
      secUtils.verifyFullSignature("POST", "/getrewardmiles", headerId, headerDate,
              headerSigBody,headerSig);
    }

    Long miles = flightService.getRewardMiles(segmentId); 
   
    return new MilesResponse(miles);
  }

  @GET
  public Response checkStatus() {
    return Response.ok("OK").build();        
  } 
  
  private String getFlightOptions(String fromAirport, String toAirport, Date fromDate, 
      Date returnDate, boolean oneWay) {
            
    // Get list of toflights as Json Array
    List<String> toFlights = flightService.getFlightByAirportsAndDepartureDate(fromAirport, 
        toAirport, fromDate);
    JsonArray toFlightsJsonArray = convertFlightListToJsonArray(toFlights);

    String options;
    
    if (oneWay) {
      options = jsonObjectFactory.createObjectBuilder()
          .add("tripFlights", jsonObjectFactory.createArrayBuilder()
          .add(jsonObjectFactory.createObjectBuilder()
              .add("numPages", 1)
              .add("flightsOptions", toFlightsJsonArray)
              .add("currentPage", 0)
              .add("hasMoreOptions", false)
              .add("pageSize", 10)))
          .add("tripLegs", 1)
          .build().toString();
    } else { 
      // Get list of returnflights as Json Array
      List<String> retFlights = flightService.getFlightByAirportsAndDepartureDate(toAirport, 
          fromAirport, returnDate);
      JsonArray retFlightsJsonArray = convertFlightListToJsonArray(retFlights);
      
      options = jsonObjectFactory.createObjectBuilder()
          .add("tripFlights", jsonObjectFactory.createArrayBuilder()
              .add(jsonObjectFactory.createObjectBuilder()
                  .add("numPages", 1)
                  .add("flightsOptions", toFlightsJsonArray)
                  .add("currentPage", 0)
                  .add("hasMoreOptions", false)
                  .add("pageSize", 10))
              .add(jsonObjectFactory.createObjectBuilder()
                  .add("numPages", 1)
                  .add("flightsOptions", retFlightsJsonArray)
                  .add("currentPage", 0)
                  .add("hasMoreOptions", false)
                  .add("pageSize", 10)))
          .add("tripLegs", 2)
          .build().toString();
    }

    return options;
  }
    
  private JsonArray convertFlightListToJsonArray(List<String> flights) {
    
    if (flights == null) {
      // empty array
      return jsonObjectFactory.createArrayBuilder().build();
    }
    
    JsonReader jsonReader = jsonReaderFactory.createReader(new StringReader(flights.toString()));
    JsonArray  flightsJsonArray = jsonReader.readArray();
    jsonReader.close();

    return flightsJsonArray;
  }

  private Date convertStringToDate(String stringDate) {  
    
    if (stringDate == null) {
      return null;
    }
    
    try {
    
      DateTimeFormatter formatter = 
          DateTimeFormatter.ofPattern("E MMM dd yyyy", Locale.getDefault());
      
      LocalDate date = LocalDate.parse(stringDate.substring(0,10) + " " + year, formatter); 
    
      return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }
}
